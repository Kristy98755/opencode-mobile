#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const MAX_BODY = 900 * 1024;

const configPath = path.join(__dirname, "config.json");
if (!fs.existsSync(configPath)) {
  console.error("config.json not found. Copy config.example.json to config.json and fill it in.");
  process.exit(1);
}
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));

const relayUrl = String(config.relayUrl || "").replace(/\/+$/, "");
const token = String(config.token || "");
const ocUrl = String(config.opencode?.url || "http://127.0.0.1:4096").replace(/\/+$/, "");
const ocUser = String(config.opencode?.username || "");
const ocPass = String(config.opencode?.password || "");
const directory = String(config.directory || "");
const autoStartServe = config.autoStartServe !== false;
const port = Number(config.port || 4096);

if (!relayUrl || !token) {
  console.error("relayUrl and token are required in config.json");
  process.exit(1);
}

let authHeader = null;
if (ocUser || ocPass) {
  authHeader = "Basic " + Buffer.from(`${ocUser}:${ocPass}`, "utf8").toString("base64");
}

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

async function opencodeFetch(pathname, init = {}, timeoutMs = 60000) {
  const headers = { ...(init.headers || {}) };
  if (authHeader) headers["authorization"] = authHeader;
  if (init.body && !headers["content-type"]) headers["content-type"] = "application/json";
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    return await fetch(ocUrl + pathname, { ...init, headers, signal: ctrl.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function healthy() {
  try {
    const res = await opencodeFetch("/global/health", {}, 5000);
    return res.ok;
  } catch {
    return false;
  }
}

let serveProcess = null;

async function ensureServer() {
  if (await healthy()) {
    log("opencode server already running at", ocUrl);
    return true;
  }
  if (!autoStartServe) {
    log("opencode server not reachable and autoStartServe is false");
    return false;
  }
  log("starting `opencode serve --port", port, "` ...");
  const env = { ...process.env };
  if (ocPass) {
    env["OPENCODE_SERVER_PASSWORD"] = ocPass;
    env["OPENCODE_SERVER_USERNAME"] = ocUser || "opencode";
  }
  serveProcess = spawn("opencode", ["serve", "--port", String(port)], {
    env,
    stdio: ["ignore", "pipe", "pipe"],
    detached: false,
    shell: true,
  });
  serveProcess.stdout?.on("data", (d) => process.stdout.write("[serve] " + d));
  serveProcess.stderr?.on("data", (d) => process.stderr.write("[serve] " + d));
  serveProcess.on("exit", (code) => {
    log("opencode serve exited with code", code);
    serveProcess = null;
  });

  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    if (await healthy()) {
      log("opencode server is up");
      return true;
    }
  }
  log("opencode server did not become healthy in time");
  return false;
}

// --- relay ---

let ws = null;
let shouldRun = true;
let sseAbort = null;
let backoff = 1000;

function wsSend(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify(obj));
    } catch (e) {
      log("ws send error:", e.message);
    }
  }
}

function truncate(bodyText) {
  if (bodyText.length <= MAX_BODY) return bodyText;
  return bodyText.slice(0, MAX_BODY) + "\n...[truncated]";
}

async function handleReq(msg) {
  const id = msg["id"];
  const method = String(msg["m"] || "GET").toUpperCase();
  const p = String(msg["p"] || "/");
  const body = msg["b"];

  if (!(await healthy())) {
    if (autoStartServe) await ensureServer();
    if (!(await healthy())) {
      wsSend({ t: "res", id, s: 503, b: { message: "opencode server offline" } });
      return;
    }
  }

  let init = { method };
  if (body !== undefined && body !== null && method !== "GET" && method !== "HEAD") {
    init.body = typeof body === "string" ? body : JSON.stringify(body);
  }

  try {
    const res = await opencodeFetch(p, init, 120000);
    const text = await res.text();
    let parsed = null;
    try {
      parsed = text ? JSON.parse(truncate(text)) : null;
    } catch {
      parsed = { raw: truncate(text) };
    }
    wsSend({ t: "res", id, s: res.status, b: parsed });
  } catch (e) {
    wsSend({ t: "res", id, s: 502, b: { message: "opencode fetch failed: " + (e.message || String(e)) } });
  }
}

async function startSse() {
  sseAbort?.abort();
  sseAbort = new AbortController();
  const sendEvents = async () => {
    try {
      const headers = {};
      if (authHeader) headers["authorization"] = authHeader;
      const res = await fetch(ocUrl + "/event", {
        headers,
        signal: sseAbort.signal,
      });
      if (!res.ok || !res.body) {
        log("SSE bad status", res.status);
        return;
      }
      log("SSE connected");
      backoff = 1000;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, idx).trim();
          buf = buf.slice(idx + 1);
          if (!line.startsWith("data:")) continue;
          const data = line.slice(5).trim();
          if (!data || data === "[DONE]") continue;
          try {
            const ev = JSON.parse(data);
            wsSend({ t: "ev", d: ev });
          } catch {
            /* skip malformed event */
          }
        }
      }
    } catch (e) {
      if (sseAbort.signal.aborted) return;
      log("SSE error:", e.message);
    }
  };
  sendEvents();
}

function connectRelay() {
  const url = `${relayUrl}/ws?role=agent&token=${encodeURIComponent(token)}`;
  log("connecting to relay", relayUrl);
  try {
    ws?.removeAllListeners?.();
    ws?.close();
  } catch {
    /* ignore */
  }
  ws = new WebSocket(url);

  ws.addEventListener("open", () => {
    log("relay connected");
    backoff = 1000;
    wsSend({ t: "presence" });
    startSse();
  });

  ws.addEventListener("message", async (event) => {
    let msg;
    try {
      msg = JSON.parse(typeof event.data === "string" ? event.data : event.data.toString());
    } catch {
      return;
    }
    if (msg["t"] === "req") {
      await handleReq(msg);
    } else if (msg["t"] === "ping") {
      wsSend({ t: "pong" });
    }
  });

  ws.addEventListener("close", () => {
    log("relay closed, reconnecting in", backoff, "ms");
    scheduleReconnect();
  });

  ws.addEventListener("error", (e) => {
    log("relay error:", e.message || e.code || "unknown");
    try {
      ws.close();
    } catch {
      /* ignore */
    }
  });
}

// heartbeat: verify the socket is really alive, reopen otherwise
setInterval(() => {
  if (!shouldRun) return;
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    log("heartbeat: socket not open, reconnecting");
    scheduleReconnect();
    return;
  }
  try {
    ws.send(JSON.stringify({ t: "ping" }));
  } catch (e) {
    log("heartbeat send failed:", e.message);
    scheduleReconnect();
  }
}, 25000);

function scheduleReconnect() {
  if (!shouldRun) return;
  setTimeout(connectRelay, backoff);
  backoff = Math.min(backoff * 2, 15000);
}

async function main() {
  log("OpenCode Mobile bridge starting");
  log("relay:", relayUrl);
  log("opencode:", ocUrl);
  if (directory) log("directory:", directory);
  await ensureServer();
  connectRelay();
}

process.on("SIGINT", () => {
  shouldRun = false;
  sseAbort?.abort();
  try {
    ws?.close();
  } catch {
    /* ignore */
  }
  if (serveProcess) {
    try {
      serveProcess.kill();
    } catch {
      /* ignore */
    }
  }
  process.exit(0);
});

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
