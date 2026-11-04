# Presets service (Cloudflare Worker)

Lets people publish presets, like them and comment **inside the app, without a GitHub account**.
Without it the library still works: publishing opens a pre-filled GitHub issue, likes and
comments live on that issue.

What lives where:
- presets: the `presets` branch of this repo (the service only opens the issue, the GitHub
  Action checks the preset and adds it);
- likes and comments: a Cloudflare D1 database (free plan: 5 GB, 5M reads and 100k writes a day).

## Set up in the browser (once, ~10 minutes, free)

1. **GitHub token.** GitHub → Settings → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token. Repository access: *Only select repositories* →
   `YurNLator`. Permissions → Repository → **Issues: Read and write**. Nothing else. Copy the token.
2. **Worker.** dash.cloudflare.com → Workers & Pages → Create → Worker → name `yurnlator-presets` →
   Deploy. Then *Edit code*, replace everything with `worker.js` from this folder → Deploy.
3. **Database.** Storage & Databases → D1 → Create database → name `yurnlator-presets`.
   Tables are created by the worker itself.
4. **Connect them.** Worker → Settings → Bindings → Add → D1 database →
   variable name **`DB`** → database `yurnlator-presets`.
5. **Settings.** Worker → Settings → Variables and Secrets → Add:
   - `REPO` (Text) = `YurNayt/YurNLator`
   - `GITHUB_TOKEN` (Secret) = the token from step 1
   - `ADMIN_KEY` (Secret) = any long password (to remove spam comments)
6. **Check.** Open `https://yurnlator-presets.<your-name>.workers.dev/` — it must show
   `{"ok":true,"service":"yurnlator-presets"}`.
7. **Switch the app to it.** On the `presets` branch edit `config.json`:
   `{ "upload_url": "https://yurnlator-presets.<your-name>.workers.dev" }`.
   Apps pick it up within ~10 minutes, no new build. `""` turns it off again.

Removing a spam comment: the comment id is in `GET /comments?id=<preset>`, then
`POST /admin/delete-comment {"key": "<ADMIN_KEY>", "comment": <id>}`.

With the command line instead: `npm i -g wrangler`, `wrangler login`, `wrangler d1 create
yurnlator-presets` (put the printed id into wrangler.toml), `wrangler secret put GITHUB_TOKEN`,
`wrangler secret put ADMIN_KEY`, `wrangler deploy`.
