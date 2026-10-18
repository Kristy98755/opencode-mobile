# OpenCode Mobile

Unofficial open-source Android remote client for [OpenCode](https://opencode.ai).
It acts as "eyes and hands" for the OpenCode instance running on your PC:
sessions, live streaming chat (text, reasoning, tool calls), permission and
question prompts, model/agent selection, and a project file browser.

```
Android app  <──WebSocket──>  Cloudflare relay  <──WebSocket──>  PC agent  <──HTTP/SSE──>  opencode serve
```

- **app/** — Kotlin + Jetpack Compose Android client (package `app.opencode.mobile`)
- **relay/** — Cloudflare Worker + Durable Object that pairs one phone with one PC (pairing token)
- **agent/** — zero-dependency Node.js bridge that talks to the local `opencode serve` API and forwards its `/event` SSE stream

## 1. Deploy the relay

```bash
cd relay
npx wrangler deploy
npx wrangler secret put PAIRING_TOKEN   # paste any random secret, e.g. 32 hex chars
```

Note the worker URL, e.g. `https://opencode-mobile-relay.<account>.workers.dev`.

## 2. Run the agent on your PC

Requires Node.js 20+ and `opencode` installed.

```bash
cd agent
cp config.example.json config.json
# edit config.json: relayUrl, token, opencode.url / username / password
node agent.mjs
```

Config fields:

| Field | Meaning |
|---|---|
| `relayUrl` | your deployed worker URL (https/wss) |
| `token` | the pairing token you set as `PAIRING_TOKEN` |
| `opencode.url` | local `opencode serve` base URL (default `http://127.0.0.1:4096`) |
| `opencode.username` / `opencode.password` | Basic-auth credentials of the serve instance (optional) |
| `autoStartServe` | spawn `opencode serve --port <port>` automatically if it is not running |
| `directory` | project directory used as the agent's working directory hint |

The agent is the only piece that ever sees your OpenCode credentials; the
phone only ever sees the relay URL and pairing token.

## 3. Install the app

- Debug build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Release build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
  (requires a keystore; see `app/build.gradle.kts` `RELEASE_*` properties)

Open the app, enter the relay URL and pairing token, tap **Connect**, then
start a session.

## Security notes

- The relay only forwards traffic between sockets that present the pairing
  token; it stores nothing.
- Use HTTPS/WSS endpoints only.
- Anyone with the pairing token can control your OpenCode instance — treat it
  like a password.

## License

MIT
