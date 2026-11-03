#!/usr/bin/env python3
"""Community presets: turns "[preset]" issues into library entries (branch "presets").

Run by .github/workflows/presets.yml. Reads the event from GITHUB_EVENT_PATH, works in DATA_DIR
(a checkout of the presets branch) and writes the reply to post on the issue to OUT_DIR/reply.md,
plus OUT_DIR/status ("ok", "rejected" or "skip") and OUT_DIR/commit_msg.

The issue body is untrusted input: it is only parsed here, never handed to a shell.
"""
import base64
import gzip
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import time

DATA = os.environ.get("DATA_DIR", "data")
OUT = os.environ.get("OUT_DIR", ".")
REPO = os.environ.get("GITHUB_REPOSITORY", "YurNayt/YurNLator")
INDEX = os.path.join(DATA, "index.json")

# Must match CommunityPreset.ALLOWED_KEYS in the app.
ALLOWED = {
    "screenSize", "graphicsDriver", "graphicsDriverConfig", "audioDriver", "dxwrapper", "dxwrapperConfig",
    "emulator", "fexcoreVersion", "box64Version", "fexcorePreset", "box64Preset", "inputType",
    "exclusiveXInput", "startupSelection", "lc_all", "envVars", "wincomponents", "syncCpuTopology",
    "frameGen", "frameGenEngine", "frameGenMultiplier", "frameGenPreset", "frameGenFlowScale", "frameGenTarget",
    "oboeProfile", "oboeApi", "oboeAdaptive", "oboeExclusive",
    "displayBackend", "fullscreenStretched", "disableXinput", "enableRelativeMouse", "disableMouse",
    "simTouchScreen", "sharpnessEffect", "sharpnessLevel", "sharpnessDenoise", "autoMesaGlVersionOverride",
    "useDisplayX", "graphicsFpsPreset", "graphicsPostFXMode", "graphicsSharpness", "graphicsFilterMode",
    "mouseWarpOverride",
    "rendererNative", "trueDisplayX", "surfaceFormat", "displayXPerformanceMode",
    "displayXPresentAtRefreshRate", "rendererPresentMode", "rendererFilterMode", "rendererSwapRB",
    "execArgs",
}
SECRET_ENV = re.compile(r"(TOKEN|SECRET|PASSW|API_?KEY|AUTH|COOKIE|SESSION|STEAM_?ID|STEAM_?USER|ACCOUNT|EMAIL|LOGIN)", re.I)
MAX_PER_DAY = 10  # presets one GitHub user can publish per 24 h


def out(name, text):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        f.write(text)


def load_index():
    try:
        with open(INDEX, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data.get("presets"), list):
            return data
    except Exception:
        pass
    return {"version": 1, "updated": 0, "presets": []}


def save_index(data):
    data["updated"] = int(time.time())
    data["presets"].sort(key=lambda e: e.get("created", 0), reverse=True)
    with open(INDEX, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")


def game_key(name):
    name = re.sub(r"\.(exe|lnk|desktop)$", "", (name or "").lower())
    return re.sub(r"[^a-z0-9]", "", name)


def text(v, limit):
    v = v if isinstance(v, str) else ("" if v is None else str(v))
    v = "".join(ch for ch in v if ch == "\n" or ch >= " ")
    return v.strip()[:limit]


def decode_block(body):
    m = re.search(r"```yurnlator-preset\s+([A-Za-z0-9+/=\s]+?)```", body or "")
    if m:
        raw = base64.b64decode(re.sub(r"\s", "", m.group(1)), validate=False)
        if len(raw) > 64 * 1024:
            raise ValueError("the preset is too large")
        data = gzip.decompress(raw)
        if len(data) > 256 * 1024:
            raise ValueError("the preset is too large")
        return json.loads(data.decode("utf-8"))
    m = re.search(r"```(?:json)?\s*(\{.*\})\s*```", body or "", re.S)
    if m:
        return json.loads(m.group(1))
    raise ValueError("no preset found in the issue (the ```yurnlator-preset block is missing or was edited)")


def clean(p):
    if not isinstance(p, dict) or p.get("format") != "yurnlator-preset":
        raise ValueError("this is not a YurNLator preset")
    settings = p.get("settings") or {}
    if not isinstance(settings, dict):
        raise ValueError("bad settings")
    s = {}
    for k, v in settings.items():
        if k not in ALLOWED:
            continue
        v = text(v, 4000).replace("\n", " ")
        if k == "envVars":
            v = " ".join(t for t in v.split() if not SECRET_ENV.search(t.split("=", 1)[0]))
            if not v:
                continue
        s[k] = v
    if not s:
        raise ValueError("the preset has no settings")
    dev = p.get("device") or {}
    req = p.get("requires") or {}
    drv = req.get("driver") or {}
    pid = re.sub(r"[^a-f0-9]", "", str(p.get("id", "")).lower())[:16]
    created = p.get("created")
    now = int(time.time())
    if not isinstance(created, int) or created <= 0 or created > now + 86400:
        created = now
    owner = re.sub(r"[^a-f0-9]", "", str(p.get("owner_hash", "")).lower())[:64]
    return {
        "format": "yurnlator-preset", "version": 1,
        "id": pid, "game": text(p.get("game"), 120), "exe": text(p.get("exe"), 120).lower(),
        "title": text(p.get("title"), 80), "notes": text(p.get("notes"), 1500),
        "author": text(p.get("author"), 40), "created": created,
        "device": {"model": text(dev.get("model"), 80), "soc": text(dev.get("soc"), 40),
                   "gpu": text(dev.get("gpu"), 60), "android": int(dev.get("android") or 0) if str(dev.get("android") or "0").isdigit() else 0},
        "app": {"version": text((p.get("app") or {}).get("version"), 40)},
        "requires": {
            "wine": text(req.get("wine"), 120), "wine_label": text(req.get("wine_label"), 120),
            "dxvk": text(req.get("dxvk"), 80), "vkd3d": text(req.get("vkd3d"), 80),
            "box64": text(req.get("box64"), 80), "fexcore": text(req.get("fexcore"), 80),
            "driver": {"id": text(drv.get("id"), 120), "label": text(drv.get("label"), 120),
                       "url": text(drv.get("url"), 400) if str(drv.get("url", "")).startswith("https://") else ""},
        },
        "settings": s,
        "owner_hash": owner,
    }


def entry(p, issue, login, via_service):
    return {
        "id": p["id"], "game": p["game"], "game_key": game_key(p["game"]), "exe": p["exe"],
        "title": p["title"], "author": p["author"], "device": p["device"]["model"],
        "soc": p["device"]["soc"], "gpu": p["device"]["gpu"], "created": p["created"],
        "app_version": p["app"]["version"], "issue": issue, "likes": 0, "comments": 0,
        "login": "" if via_service else login,
    }


def publish(issue, body, login, via_service):
    p = clean(decode_block(body))
    if not p["game"]:
        raise ValueError("the preset has no game name")
    data = load_index()
    if not via_service:
        day = int(time.time()) - 86400
        recent = [e for e in data["presets"] if e.get("login") == login and e.get("published", 0) > day]
        if len(recent) >= MAX_PER_DAY:
            raise ValueError("too many presets from this account today, try again tomorrow")
    ids = {e["id"] for e in data["presets"]}
    if not p["id"] or p["id"] in ids:
        p["id"] = secrets.token_hex(6)
    os.makedirs(os.path.join(DATA, "p"), exist_ok=True)
    with open(os.path.join(DATA, "p", p["id"] + ".json"), "w", encoding="utf-8") as f:
        json.dump(p, f, ensure_ascii=False, indent=1)
        f.write("\n")
    e = entry(p, issue, login, via_service)
    e["published"] = int(time.time())
    data["presets"].append(e)
    save_index(data)
    out("commit_msg", f"preset {p['id']}: {p['game']} ({p['title'] or 'preset'}) #{issue}")
    hw = " · ".join(x for x in [p["device"]["model"], p["device"]["gpu"], p["device"]["soc"]] if x)
    return (f"✅ Published to the YurNLator preset library as **{p['id']}**: *{p['game']}*"
            f"{(' — ' + p['title']) if p['title'] else ''} ({hw}).\n\n"
            "It shows up in the app (game page → Community presets) within a few minutes.\n\n"
            "This issue is the preset's page: 👍 on it counts as a like, comments are reviews "
            "(what FPS you get, what works).")


def delete(issue, body, login):
    pid = re.search(r"id:\s*([a-f0-9]{6,16})", body or "")
    tok = re.search(r"token:\s*([a-f0-9]{16,64})", body or "")
    if not pid:
        raise ValueError("no preset id")
    pid = pid.group(1)
    data = load_index()
    e = next((x for x in data["presets"] if x["id"] == pid), None)
    if e is None:
        raise ValueError(f"preset {pid} is not in the library")
    path = os.path.join(DATA, "p", pid + ".json")
    owner = ""
    try:
        with open(path, encoding="utf-8") as f:
            owner = json.load(f).get("owner_hash", "")
    except Exception:
        pass
    by_token = tok and owner and hashlib.sha256(tok.group(1).encode()).hexdigest() == owner
    by_login = e.get("login") and e.get("login") == login
    if not (by_token or by_login):
        raise ValueError("only the phone (or GitHub account) that published this preset can remove it")
    data["presets"] = [x for x in data["presets"] if x["id"] != pid]
    if os.path.exists(path):
        os.remove(path)
    save_index(data)
    out("commit_msg", f"remove preset {pid} #{issue}")
    out("close_issue", str(e.get("issue") or ""))
    return f"🗑️ Preset **{pid}** removed from the library."


def refresh():
    """Likes (👍 on the preset's issue) and comment counts."""
    data = load_index()
    changed = False
    for e in data["presets"]:
        n = e.get("issue")
        if not n:
            continue
        try:
            raw = subprocess.run(["gh", "api", f"repos/{REPO}/issues/{n}"], capture_output=True, text=True, timeout=30).stdout
            info = json.loads(raw)
        except Exception:
            continue
        likes = int((info.get("reactions") or {}).get("+1", 0))
        comments = max(0, int(info.get("comments", 0)) - 1)  # minus the bot's own reply
        if likes != e.get("likes") or comments != e.get("comments"):
            e["likes"], e["comments"] = likes, comments
            changed = True
    if changed:
        save_index(data)
        out("commit_msg", "presets: likes and comments")


def main():
    for f in ("reply.md", "status", "commit_msg", "close_issue"):
        try:
            os.remove(os.path.join(OUT, f))
        except FileNotFoundError:
            pass
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    if event_name != "issues":
        refresh()
        out("status", "skip")
        return
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as f:
        event = json.load(f)
    issue = event["issue"]
    title = issue.get("title") or ""
    body = issue.get("body") or ""
    login = (issue.get("user") or {}).get("login", "")
    # Issues opened by the upload service carry this marker; the service is the only one with the token
    # that opens issues as the repository owner, so a marker from anyone else is ignored.
    via_service = "<!-- via:upload-service -->" in body and login == REPO.split("/")[0]
    try:
        if title.startswith("[preset-delete]"):
            reply = delete(issue["number"], body, login)
        elif title.startswith("[preset]"):
            reply = publish(issue["number"], body, login, via_service)
        else:
            out("status", "skip")
            return
        out("reply.md", reply)
        out("status", "ok")
    except Exception as ex:  # noqa: BLE001 — every failure becomes a reply on the issue
        out("reply.md", f"❌ This preset wasn't added: {ex}.\n\nPublish it again from the app "
                        "(game page → Community presets → Share my settings) without editing the issue text.")
        out("status", "rejected")


if __name__ == "__main__":
    main()
    sys.exit(0)
