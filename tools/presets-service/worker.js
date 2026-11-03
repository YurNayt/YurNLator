// YurNLator community presets: optional anonymous upload service (Cloudflare Worker).
//
// Without it, the app publishes a preset as a GitHub issue the user submits in the browser
// (a GitHub account is needed). With it, the app sends the preset here and this worker opens
// that issue itself, so users publish from inside the app without an account. The GitHub
// Action (.github/workflows/presets.yml) then checks the preset and adds it to the library.
//
// Secrets / vars (see README.md):
//   GITHUB_TOKEN  fine-grained token of the repository owner: Issues read & write on the repo
//   REPO          "YurNayt/YurNLator"
//
// POST /upload {title, body}  ->  {ok: true, issue: <number>}

const MAX_BODY = 12000;
const PER_HOUR = 6; // uploads per IP per hour

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") return json({ ok: true, service: "yurnlator-presets" });
    if (request.method !== "POST" || url.pathname !== "/upload") return json({ ok: false, error: "not found" }, 404);

    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    if (!(await allow(ip))) return json({ ok: false, error: "rate_limited" }, 429);

    let data;
    try { data = await request.json(); } catch { return json({ ok: false, error: "bad json" }, 400); }
    const title = String(data.title || "").slice(0, 200);
    const body = String(data.body || "");
    const isPreset = title.startsWith("[preset] ") && body.includes("```yurnlator-preset");
    const isDelete = title.startsWith("[preset-delete] ") && body.includes("yurnlator-preset-delete");
    if (!(isPreset || isDelete) || body.length > MAX_BODY) return json({ ok: false, error: "invalid" }, 400);

    const res = await fetch(`https://api.github.com/repos/${env.REPO}/issues`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.GITHUB_TOKEN}`,
        accept: "application/vnd.github+json",
        "user-agent": "yurnlator-presets-service",
        "content-type": "application/json",
      },
      body: JSON.stringify({ title, body: body + "\n\n<!-- via:upload-service -->\n" }),
    });
    if (!res.ok) return json({ ok: false, error: "github " + res.status }, 502);
    const issue = await res.json();
    return json({ ok: true, issue: issue.number });
  },
};

// Simple per-IP limit on the edge cache (good enough against floods; the Action has its own checks).
async function allow(ip) {
  const cache = caches.default;
  const hour = Math.floor(Date.now() / 3600000);
  const key = new Request(`https://rate.local/${encodeURIComponent(ip)}/${hour}`);
  const hit = await cache.match(key);
  const n = hit ? parseInt(await hit.text(), 10) || 0 : 0;
  if (n >= PER_HOUR) return false;
  await cache.put(key, new Response(String(n + 1), { headers: { "cache-control": "max-age=3600" } }));
  return true;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
}
