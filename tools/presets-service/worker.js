// YurNLator community presets service (Cloudflare Worker + D1).
//
// Everything the app does with presets, without leaving the app and without a GitHub account:
//   POST /upload   {title, body}              publish / remove a preset: opens the "[preset]" or
//                                             "[preset-delete]" issue that the GitHub Action turns
//                                             into a library entry (the library stays on GitHub)
//   GET  /social?ids=a,b,c                    {a: {likes, comments, liked}, ...}   (&device=…)
//   POST /like     {id, device}               toggles this phone's like  -> {ok, likes, liked}
//   GET  /comments?id=a                       [{id, name, text, device, created}]
//   POST /comment  {id, device, name, text, hw} -> {ok, comment}
//   POST /admin/delete-comment {key, comment} (ADMIN_KEY) removes a spam comment
//
// Bindings (see README.md):
//   DB            D1 database (tables are created on first use)
//   GITHUB_TOKEN  secret: fine-grained token, Issues read & write on the repo only
//   ADMIN_KEY     secret: any long password, for removing comments
//   REPO          var: "YurNayt/YurNLator"

const MAX_BODY = 12000;
const LIMITS = { upload: 6, comment: 20, like: 120 }; // per IP per hour

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    try {
      if (request.method === "GET" && path === "/") return json({ ok: true, service: "yurnlator-presets" });
      if (request.method === "POST" && path === "/upload") return await upload(request, env);
      await schema(env);
      if (request.method === "GET" && path === "/social") return await social(url, env);
      if (request.method === "POST" && path === "/like") return await like(request, env);
      if (request.method === "GET" && path === "/comments") return await comments(url, env);
      if (request.method === "POST" && path === "/comment") return await comment(request, env);
      if (request.method === "POST" && path === "/admin/delete-comment") return await adminDelete(request, env);
      return json({ ok: false, error: "not found" }, 404);
    } catch (e) {
      return json({ ok: false, error: "server", detail: String(e).slice(0, 200) }, 500);
    }
  },
};

// ── publishing ──────────────────────────────────────────────────────────────────────────────

async function upload(request, env) {
  if (!(await allow(request, "upload"))) return json({ ok: false, error: "rate_limited" }, 429);
  const data = await body(request);
  const title = String(data.title || "").slice(0, 200);
  const text = String(data.body || "");
  const isPreset = title.startsWith("[preset] ") && text.includes("```yurnlator-preset");
  const isDelete = title.startsWith("[preset-delete] ") && text.includes("yurnlator-preset-delete");
  if (!(isPreset || isDelete) || text.length > MAX_BODY) return json({ ok: false, error: "invalid" }, 400);

  const res = await fetch(`https://api.github.com/repos/${env.REPO}/issues`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.GITHUB_TOKEN}`,
      accept: "application/vnd.github+json",
      "user-agent": "yurnlator-presets-service",
      "content-type": "application/json",
    },
    body: JSON.stringify({ title, body: text + "\n\n<!-- via:upload-service -->\n" }),
  });
  if (!res.ok) return json({ ok: false, error: "github " + res.status }, 502);
  const issue = await res.json();
  return json({ ok: true, issue: issue.number });
}

// ── likes and comments ──────────────────────────────────────────────────────────────────────

let schemaReady = false;
async function schema(env) {
  if (schemaReady) return;
  await env.DB.batch([
    env.DB.prepare("CREATE TABLE IF NOT EXISTS likes (preset TEXT NOT NULL, device TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (preset, device))"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS comments (id INTEGER PRIMARY KEY AUTOINCREMENT, preset TEXT NOT NULL, device TEXT NOT NULL, name TEXT NOT NULL, hw TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)"),
    env.DB.prepare("CREATE INDEX IF NOT EXISTS comments_preset ON comments (preset, created)"),
  ]);
  schemaReady = true;
}

const PRESET = /^[a-f0-9]{6,16}$/;
const DEVICE = /^[a-f0-9]{16,64}$/;

async function social(url, env) {
  const ids = (url.searchParams.get("ids") || "").split(",").filter((s) => PRESET.test(s)).slice(0, 200);
  const device = url.searchParams.get("device") || "";
  const out = {};
  if (!ids.length) return json(out);
  const marks = ids.map(() => "?").join(",");
  const likes = await env.DB.prepare(`SELECT preset, COUNT(*) AS n FROM likes WHERE preset IN (${marks}) GROUP BY preset`).bind(...ids).all();
  const comm = await env.DB.prepare(`SELECT preset, COUNT(*) AS n FROM comments WHERE preset IN (${marks}) GROUP BY preset`).bind(...ids).all();
  for (const id of ids) out[id] = { likes: 0, comments: 0, liked: false };
  for (const r of likes.results) out[r.preset].likes = r.n;
  for (const r of comm.results) out[r.preset].comments = r.n;
  if (DEVICE.test(device)) {
    const mine = await env.DB.prepare(`SELECT preset FROM likes WHERE device = ? AND preset IN (${marks})`).bind(device, ...ids).all();
    for (const r of mine.results) out[r.preset].liked = true;
  }
  return json(out);
}

async function like(request, env) {
  if (!(await allow(request, "like"))) return json({ ok: false, error: "rate_limited" }, 429);
  const d = await body(request);
  const id = String(d.id || ""), device = String(d.device || "");
  if (!PRESET.test(id) || !DEVICE.test(device)) return json({ ok: false, error: "invalid" }, 400);
  const had = await env.DB.prepare("SELECT 1 FROM likes WHERE preset = ? AND device = ?").bind(id, device).first();
  if (had) await env.DB.prepare("DELETE FROM likes WHERE preset = ? AND device = ?").bind(id, device).run();
  else await env.DB.prepare("INSERT INTO likes (preset, device, created) VALUES (?, ?, ?)").bind(id, device, now()).run();
  const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM likes WHERE preset = ?").bind(id).first();
  return json({ ok: true, likes: n.n, liked: !had });
}

async function comments(url, env) {
  const id = url.searchParams.get("id") || "";
  if (!PRESET.test(id)) return json([]);
  const r = await env.DB.prepare("SELECT id, name, hw, text, created, device FROM comments WHERE preset = ? ORDER BY created ASC LIMIT 300").bind(id).all();
  // The device id itself never leaves the server; a short tag lets the app mark "you".
  const out = [];
  for (const c of r.results) out.push({ id: c.id, name: c.name, hw: c.hw, text: c.text, created: c.created, tag: await tag(c.device) });
  return json(out);
}

async function comment(request, env) {
  if (!(await allow(request, "comment"))) return json({ ok: false, error: "rate_limited" }, 429);
  const d = await body(request);
  const id = String(d.id || ""), device = String(d.device || "");
  const text = clean(d.text, 1000);
  if (!PRESET.test(id) || !DEVICE.test(device) || text.length < 2) return json({ ok: false, error: "invalid" }, 400);
  const name = clean(d.name, 40) || "anonymous";
  const hw = clean(d.hw, 80);
  const created = now();
  const r = await env.DB.prepare("INSERT INTO comments (preset, device, name, hw, text, created) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(id, device, name, hw, text, created).run();
  return json({ ok: true, comment: { id: r.meta.last_row_id, name, hw, text, created, tag: await tag(device) } });
}

async function adminDelete(request, env) {
  const d = await body(request);
  if (!env.ADMIN_KEY || d.key !== env.ADMIN_KEY) return json({ ok: false, error: "forbidden" }, 403);
  await env.DB.prepare("DELETE FROM comments WHERE id = ?").bind(Number(d.comment) || 0).run();
  return json({ ok: true });
}

// ── helpers ─────────────────────────────────────────────────────────────────────────────────

function now() { return Math.floor(Date.now() / 1000); }

function clean(v, limit) {
  return String(v || "").replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, "").trim().slice(0, limit);
}

async function body(request) {
  try { return await request.json(); } catch { return {}; }
}

async function tag(device) {
  const h = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("yurn:" + device));
  return [...new Uint8Array(h)].slice(0, 6).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Per-IP limit on the edge cache (good enough against floods; the Action checks presets itself).
async function allow(request, kind) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const cache = caches.default;
  const hour = Math.floor(Date.now() / 3600000);
  const key = new Request(`https://rate.local/${kind}/${encodeURIComponent(ip)}/${hour}`);
  const hit = await cache.match(key);
  const n = hit ? parseInt(await hit.text(), 10) || 0 : 0;
  if (n >= LIMITS[kind]) return false;
  await cache.put(key, new Response(String(n + 1), { headers: { "cache-control": "max-age=3600" } }));
  return true;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}
