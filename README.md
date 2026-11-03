# YurNLator community presets

Game settings shared by YurNLator users. This branch is written by the
[Community presets](../../blob/main/.github/workflows/presets.yml) workflow; don't edit it by hand
(except `config.json`).

- `index.json` — every preset: game, title, author, device, likes, comments, issue number.
- `p/<id>.json` — the preset itself (format `yurnlator-preset` v1).
- `config.json` — `upload_url`: the optional anonymous upload service (see `tools/presets-service` on main).

To publish: in the app open a game → **Community presets** → **Share my settings**.
Each preset has an issue labelled `preset`: 👍 on it is a like, comments are reviews.
