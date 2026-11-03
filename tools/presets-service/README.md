# Presets upload service (optional)

Lets people publish community presets from inside the app **without a GitHub account**.
Without it everything still works: the app opens a pre-filled GitHub issue and the user taps Submit.

## Set up (once, ~10 minutes, free)

1. Create a token: GitHub → Settings → Developer settings → Fine-grained tokens → Generate.
   Repository access: only `YurNayt/YurNLator`. Permissions: **Issues: Read and write**. Nothing else.
2. Create a free Cloudflare account, then in this folder:
   ```
   npm i -g wrangler
   wrangler login
   wrangler secret put GITHUB_TOKEN     # paste the token
   wrangler deploy
   ```
   It prints the address, e.g. `https://yurnlator-presets.<you>.workers.dev`.
3. Tell the app about it: on the `presets` branch edit `config.json`:
   ```json
   { "upload_url": "https://yurnlator-presets.<you>.workers.dev" }
   ```
   Apps pick it up within ~10 minutes; no new build needed. Set it back to `""` to turn it off.

The service only opens `[preset]` / `[preset-delete]` issues; the GitHub Action
(`.github/workflows/presets.yml`) checks every preset before it enters the library.
