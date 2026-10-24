#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import http from "node:http";
import https from "node:https";
import { spawn } from "node:child_process";
import net from "node:net";
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

// single-instance guard: the autostart task and a manually started agent must
// never run at once — the relay would deliver every wake to both and each turn
// would POST /session/{id}/message twice.
{
  const lockFile = path.join(os.tmpdir(), "ocm-agent.lock");
  try {
    const old = parseInt(fs.readFileSync(lockFile, "utf8").trim(), 10);
    if (old && old !== process.pid) {
      process.kill(old, 0); // throws when the pid is gone (stale lock)
      console.error("another agent instance is already running (pid " + old + "), exiting");
      process.exit(1);
    }
  } catch (e) {
    if (e && e.code && e.code !== "ESRCH") {
      /* unreadable lock: fall through and take over */
    }
  }
  try {
    fs.writeFileSync(lockFile, String(process.pid));
  } catch {
    /* best effort */
  }
  process.on("exit", () => {
    try {
      fs.unlinkSync(lockFile);
    } catch {
      /* ignore */
    }
  });
}

// undici (Node's fetch) hard-caps idle response time at 300s (headersTimeout /
// bodyTimeout) and an AbortController cannot raise it: a blocking POST /message
// whose turn runs longer dies with "fetch failed" right at the 5-minute mark.
// Long requests therefore bypass fetch and speak raw HTTP with a total-time
// budget instead (we never ask for compressed responses, so no decompressor).
function opencodeRaw(pathname, init = {}, timeoutMs = 60000) {
  return new Promise((resolve, reject) => {
    const headers = { ...(init.headers || {}) };
    if (authHeader) headers["authorization"] = authHeader;
    const method = String(init.method || "GET").toUpperCase();
    let body = null;
    if (init.body !== undefined && init.body !== null && method !== "GET" && method !== "HEAD") {
      body = Buffer.from(typeof init.body === "string" ? init.body : JSON.stringify(init.body), "utf8");
      if (!headers["content-type"]) headers["content-type"] = "application/json";
      headers["content-length"] = String(body.length);
    }
    let settled = false;
    let budget = null;
    const fail = (e) => {
      if (settled) return;
      settled = true;
      if (budget) clearTimeout(budget);
      reject(e instanceof Error ? e : new Error(String(e)));
    };
    const url = new URL(ocUrl + pathname);
    const mod = url.protocol === "https:" ? https : http;
    const req = mod.request(url, { method, headers }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("error", fail);
      res.on("end", () => {
        if (settled) return;
        settled = true;
        if (budget) clearTimeout(budget);
        const text = Buffer.concat(chunks).toString("utf8");
        const status = res.statusCode || 500;
        resolve({
          status,
          ok: status >= 200 && status < 300,
          text: async () => text,
          json: async () => JSON.parse(text),
        });
      });
    });
    req.on("error", fail);
    budget = setTimeout(() => fail(new Error("opencode timeout after " + timeoutMs + "ms")), timeoutMs);
    if (body) req.write(body);
    req.end();
  });
}

async function opencodeFetch(pathname, init = {}, timeoutMs = 60000) {
  if (timeoutMs > 250000) return opencodeRaw(pathname, init, timeoutMs);
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
let ensurePromise = null;

function portListening() {
  return new Promise((resolve) => {
    const s = net.connect({ port, host: "127.0.0.1" });
    let settled = false;
    const finish = (ok) => {
      if (settled) return;
      settled = true;
      try { s.destroy(); } catch {}
      resolve(ok);
    };
    s.once("connect", () => finish(true));
    s.once("error", () => finish(false));
    setTimeout(() => finish(false), 3000);
  });
}

function ensureServer() {
  if (!ensurePromise) {
    ensurePromise = doEnsureServer().finally(() => {
      ensurePromise = null;
    });
  }
  return ensurePromise;
}

let lastMaybeEnsure = 0;
function maybeEnsure() {
  if (!autoStartServe) return;
  const now = Date.now();
  if (now - lastMaybeEnsure < 30000) return;
  lastMaybeEnsure = now;
  ensureServer().catch((e) => log("ensureServer error:", (e && e.message) || e));
}

async function doEnsureServer() {
  if (await healthy()) {
    log("opencode server already running at", ocUrl);
    return true;
  }
  if (!autoStartServe) {
    log("opencode server not reachable and autoStartServe is false");
    return false;
  }
  if (await portListening()) {
    log("port", port, "is occupied by another opencode instance, not spawning a duplicate");
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
  serveProcess.on("error", (e) => {
    log("opencode serve spawn error:", (e && e.message) || e);
    serveProcess = null;
  });
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
let backoff = 1000;
let lastInAt = 0;
let reconnectTimer = null;
const STALE_MS = 120000;

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

function slimProvider(j) {
  const all = Array.isArray(j && j.all) ? j.all : [];
  return {
    all: all.map((p) => ({
      id: p.id,
      name: p.name,
      source: p.source,
      models: Object.fromEntries(
        Object.entries(p.models || {}).map(([k, m]) => [
          k,
          { id: m.id, providerID: m.providerID, name: m.name, family: m.family },
        ]),
      ),
    })),
    default: (j && j.default) || {},
    connected: (j && j.connected) || [],
  };
}

async function handleReq(msg) {
  const id = msg["id"];
  const method = String(msg["m"] || "GET").toUpperCase();
  const p = String(msg["p"] || "/");
  const body = msg["b"];
  log("req <-", id, method, p);
  const started = Date.now();

  if (!(await healthy())) {
    maybeEnsure();
    wsSend({ t: "res", id, s: 503, b: { message: "opencode server offline" } });
    return;
  }

  let init = { method };
  if (body !== undefined && body !== null && method !== "GET" && method !== "HEAD") {
    init.body = typeof body === "string" ? body : JSON.stringify(body);
  }

  const tailMatch = /^\/session\/([^/]+)\/messages-tail$/.exec(p.split("?")[0]);
  if (tailMatch) {
    await handleMessagesTail(id, method, p, tailMatch[1]);
    return;
  }

  // Async prompt wakes are unreliable in both shapes: the legacy in-memory
  // `prompt_async` wake is silently lost across processes, and the durable v2
  // queue (delivery:"queue") admits inputs but its drain loop never starts on
  // opencode 1.19.x serve (no loop/process/stream, no error). Both shapes are
  // therefore acknowledged at once and handed to the agent's durable prompt
  // queue (persisted receipt + blocking POST /message + store-based admission
  // retries). The reply reaches the app via SSE.
  const paMatch = /^\/session\/([^/]+)\/prompt_async$/.exec(p.split("?")[0]);
  if (method === "POST" && paMatch && body && typeof body === "object") {
    handlePromptWake(id, paMatch[1], body, started, "legacy");
    return;
  }
  const v2Match = /^\/api\/session\/([^/]+)\/prompt$/.exec(p.split("?")[0]);
  if (method === "POST" && v2Match && body && typeof body === "object") {
    handlePromptWake(id, v2Match[1], body, started, "v2");
    return;
  }

  // opencode 1.19.x session-scoped v2 question routes are broken (list always
  // empty, reply 404 QuestionNotFoundError) while the legacy /question routes
  // work — translate v2 into legacy + the session's directory scoping.
  const qMatch = /^\/api\/session\/([^/]+)\/question(?:\/([^/]+)\/(reply|reject))?$/.exec(p.split("?")[0]);
  if (method === "GET" && qMatch && !qMatch[2]) {
    await handleQuestionList(id, qMatch[1], started);
    return;
  }
  if (method === "POST" && qMatch && qMatch[2] && qMatch[3]) {
    await handleQuestionAction(id, qMatch[1], qMatch[2], qMatch[3], body, started);
    return;
  }

  // opencode 1.19.x answers /session/status with {} even mid-turn; the app's
  // busy reconciliation depends on it, so merge in the turns the agent can see.
  if (method === "GET" && p.split("?")[0] === "/session/status") {
    await handleSessionStatus(id, started);
    return;
  }

  // opencode's file API is jailed to the session workdir ("Path escapes the
  // location" 500 for anything above it). The agent lives on the PC, so it
  // serves listings/reads itself and expands "~" to the user's home dir.
  const fp = p.split("?")[0];
  if (method === "GET" && fp === "/file") {
    await handleFileList(id, p, started);
    return;
  }
  if (method === "GET" && fp === "/file/content") {
    await handleFileContent(id, p, started);
    return;
  }
  if (method === "GET" && fp === "/file/raw") {
    await handleFileRaw(id, p, started);
    return;
  }
  if (method === "POST" && fp === "/file/move") {
    await handleFileTransfer(id, body, started, false);
    return;
  }
  if (method === "POST" && fp === "/file/copy") {
    await handleFileTransfer(id, body, started, true);
    return;
  }
  if (method === "POST" && fp === "/file/delete") {
    await handleFileDelete(id, body, started);
    return;
  }
  if (method === "POST" && fp === "/file/mkdir") {
    await handleFileMkdir(id, body, started);
    return;
  }

  try {
    // prompt blocks until the whole turn finishes (turns here run for tens of
    // minutes), so this is only a safety net — the app streams via SSE anyway.
    const ocTimeout = method === "POST" && p.split("?")[0].endsWith("/message") ? 900000 : 120000;
    const res = await opencodeFetch(p, init, ocTimeout);
    const text = await res.text();
    let parsed = null;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      parsed = { raw: truncate(text) };
    }
    let outBytes = text.length;
    if (p.split("?")[0] === "/provider" && parsed && Array.isArray(parsed.all)) {
      parsed = slimProvider(parsed);
      outBytes = JSON.stringify(parsed).length;
    }
    const pPath = p.split("?")[0];
    if (
      parsed &&
      (pPath === "/session" ||
        pPath === "/api/session" ||
        (/^\/session\/[^/]+$/.test(pPath) && !pPath.endsWith("/model")))
    ) {
      try {
        collectSessionDirs(parsed);
      } catch {
        /* never fail a response because of dir discovery */
      }
    }
    wsSend({ t: "res", id, s: res.status, b: parsed });
    log("res ->", id, res.status, "bytes=" + outBytes, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 502, b: { message: "opencode fetch failed: " + (e.message || String(e)) } });
    log("res ->", id, 502, "fetch error:", e.message, "ms=" + (Date.now() - started));
  }
}

// ---- async prompt wakes ---------------------------------------------------
// Every wake (legacy prompt_async or durable v2 prompt) is acked immediately
// and executed through the agent's own durable queue: a receipt is persisted
// to %TEMP%\opencode\prompt_queue.json before the ack leaves, then delivered
// as a blocking POST /session/{sid}/message inside a per-session chain.
// Admission (the user message visible in the store) and retries make delivery
// survive crashes, timeouts and opencode restarts; the reply streams to the
// app through the SSE bridge.

const blockChains = new Map(); // sessionID -> Promise (serialize turns per session)

// ---- in-flight tracking ----------------------------------------------------
// opencode 1.19.x `GET /session/status` is empty even mid-turn, so the agent
// derives turn activity itself: a pending ff-block POST for the session plus
// the age of its last message.* SSE event. Surfaced as `inFlight` on
// messages-tail and merged into /session/status so the app can keep its busy
// flag and spinner alive while the message store looks frozen.
const ACTIVE_WINDOW_MS = 30000;
const activeBlockTurns = new Map(); // sessionID -> running ff-block count
const lastMessageEvAt = new Map(); // sessionID -> Date.now() of last live message.* event

function inFlight(sid) {
  if ((activeBlockTurns.get(sid) || 0) > 0) return true;
  const at = lastMessageEvAt.get(sid);
  return at !== undefined && Date.now() - at < ACTIVE_WINDOW_MS;
}

function enqueueBlock(sid, fn) {
  const prev = blockChains.get(sid) || Promise.resolve();
  const next = prev.then(fn, fn);
  blockChains.set(sid, next.catch(() => {}));
  return next;
}

function partsFromWake(body, shape) {
  const parts = [];
  if (shape === "v2") {
    const prompt = (body && body.prompt) || {};
    if (typeof prompt.text === "string" && prompt.text) parts.push({ type: "text", text: prompt.text });
    for (const f of Array.isArray(prompt.files) ? prompt.files : []) {
      if (!f || !f.uri) continue;
      parts.push({
        type: "file",
        url: f.uri,
        ...(f.name ? { filename: f.name } : {}),
        ...(f.mime ? { mime: f.mime } : {}),
      });
    }
  } else {
    for (const x of Array.isArray(body && body.parts) ? body.parts : []) {
      if (!x) continue;
      if (x.type === "text" && x.text) parts.push({ type: "text", text: x.text });
      else if (x.type === "file" && x.url) {
        parts.push({
          type: "file",
          url: x.url,
          ...(x.filename || x.name ? { filename: x.filename || x.name } : {}),
          ...(x.mime ? { mime: x.mime } : {}),
        });
      }
    }
  }
  return parts;
}

// ---- durable prompt queue ---------------------------------------------------
// Every wake is written to %TEMP%\opencode\prompt_queue.json BEFORE its ack
// leaves, so the receipt survives crashes and dropped websockets. Delivery is
// one blocking POST /session/{sid}/message; "admission" (the wake's user
// message present in the store, observed ~36s after a healthy POST, seconds
// normally) is the source of truth. An attempt is retried ONLY when its POST
// visibly failed AND the store lacks the message — a slow-but-admitted POST is
// never resent (that would double the turn) and a 2xx POST counts as delivery
// even if the matcher sees nothing (the turn already ran).
const QUEUE_FILE = path.join(os.tmpdir(), "opencode", "prompt_queue.json");
const promptQueue = new Map(); // qid -> { qid, sid, shape, parts, agent, text, state, ts, attempts, error, recovered }
const ADMISSION_WAIT_MS = 180000;
const ADMISSION_POLL_MS = 2000;
const MAX_DELIVERY_ATTEMPTS = 3;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function saveQueue() {
  try {
    const cutoff = Date.now() - 86400000;
    const keep = [...promptQueue.values()].filter(
      (e) => !((e.state === "done" || e.state === "failed") && e.ts < cutoff),
    );
    if (keep.length > 200) keep.splice(0, keep.length - 200);
    const keepSet = new Set(keep);
    for (const e of [...promptQueue.values()]) if (!keepSet.has(e)) promptQueue.delete(e.qid);
    fs.mkdirSync(path.dirname(QUEUE_FILE), { recursive: true });
    const tmp = QUEUE_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(keep));
    fs.renameSync(tmp, QUEUE_FILE);
  } catch (e) {
    log("queue save err:", e.message || String(e));
  }
}

function loadQueue() {
  try {
    const arr = JSON.parse(fs.readFileSync(QUEUE_FILE, "utf8"));
    if (!Array.isArray(arr)) return;
    for (const e of arr) {
      if (!e || !e.qid || !e.sid || !Array.isArray(e.parts)) continue;
      if (e.state === "queued" || e.state === "sending") {
        // the process died between receipt and delivery — restart it
        e.state = "queued";
        e.recovered = true;
      }
      promptQueue.set(e.qid, e);
    }
    if (promptQueue.size) log("queue restored:", promptQueue.size, "entries");
  } catch {
    /* no queue file yet */
  }
}

function recoverQueue() {
  for (const e of [...promptQueue.values()]) {
    if (e.state !== "queued" || !e.recovered) continue;
    log("queue recovering", e.qid, e.sid, "attempts=" + (e.attempts || 0));
    enqueueBlock(e.sid, () => deliverWake(e));
  }
}

// Admission: the store holds a user message with this wake's exact text created
// around the wake. The list is creation-ordered, so the scan runs newest-first.
async function messageAdmitted(entry) {
  let list;
  try {
    list = await fetchSessionMessages(entry.sid);
  } catch {
    return false;
  }
  if (!entry.text) return false;
  const cut = entry.ts - 60000;
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i];
    const inf = m && m.info;
    if (!inf) continue;
    const tc = (inf.time && (inf.time.created || inf.time.start)) || 0;
    if (tc && tc < cut) break;
    if (inf.role !== "user") continue;
    const txt = (Array.isArray(m.parts) ? m.parts : [])
      .filter((p) => p.type === "text")
      .map((p) => p.text || "")
      .join("");
    if (txt === entry.text) return true;
  }
  return false;
}

function handlePromptWake(id, sid, body, started, shape) {
  const parts = partsFromWake(body, shape);
  const agent = typeof body.agent === "string" && body.agent ? body.agent : null;
  const entry = {
    qid: "pq_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 6),
    sid,
    shape,
    parts,
    agent,
    text: parts.filter((p) => p.type === "text").map((p) => p.text || "").join(""),
    state: "queued",
    ts: Date.now(),
    attempts: 0,
    error: null,
    recovered: false,
  };
  promptQueue.set(entry.qid, entry);
  saveQueue(); // durable receipt BEFORE the ack goes out
  enqueueBlock(sid, () => deliverWake(entry));

  if (shape === "v2") {
    wsSend({
      t: "res",
      id,
      s: 200,
      b: {
        data: {
          admittedSeq: Date.now() % 1000000,
          id: "msg_local_" + Date.now().toString(36),
          sessionID: sid,
          prompt: body.prompt || {},
          delivery: "queue",
          timeCreated: Date.now(),
          queueID: entry.qid,
        },
      },
    });
  } else {
    wsSend({ t: "res", id, s: 204, b: null });
  }
  log("res ->", id, shape, "wake accepted (queued " + entry.qid + ")", "ms=" + (Date.now() - started));
}

async function deliverWake(entry) {
  const sid = entry.sid;
  activeBlockTurns.set(sid, (activeBlockTurns.get(sid) || 0) + 1);
  lastMessageEvAt.set(sid, Date.now());
  let pendingCarrier = null; // the live blocking POST, if any
  try {
    // Recovery / duplicate guard: a previous process (or a slow attempt) may
    // have admitted this wake already — poll the store before ever POSTing.
    if (entry.recovered) {
      for (let i = 0; i < 30; i++) {
        if (await messageAdmitted(entry)) break;
        await sleep(2000);
      }
      if (await messageAdmitted(entry)) {
        entry.state = "done";
        entry.error = null;
        saveQueue();
        log("wake-", entry.qid, sid, "recovered (already admitted)");
        return;
      }
      entry.recovered = false;
    }
    if (entry.agent) {
      const ar = await opencodeFetch(
        `/api/session/${sid}/agent`,
        { method: "POST", body: JSON.stringify({ agent: entry.agent }) },
        15000,
      );
      log("ff-agent ->", sid, ar.status);
    }
    for (let attempt = 1; attempt <= MAX_DELIVERY_ATTEMPTS; attempt++) {
      // Pre-POST admission is a duplicate guard for RETRIES and recovered
      // entries only. On a fresh first attempt there is nothing of ours to
      // collide with yet — matching the user simply sending the same text
      // again here swallowed a real message (confirmed e2e: "acked, done,
      // never reached the store"). Without per-wake client ids a repeat
      // cannot be told from a duplicate, so we accept the rare double turn
      // over the lost message.
      if (attempt > 1 && (await messageAdmitted(entry))) {
        entry.state = "done";
        entry.error = null;
        saveQueue();
        log("wake-", entry.qid, sid, "admitted before POST (attempt " + attempt + ")");
        return;
      }
      entry.attempts = attempt;
      entry.state = "sending";
      entry.error = null;
      saveQueue();
      log("wake-", entry.qid, sid, "POST attempt", attempt);
      let carrierSettled = false;
      let carrierErr = null;
      const carrier = opencodeFetch(
        `/session/${sid}/message`,
        { method: "POST", body: JSON.stringify({ parts: entry.parts }) },
        900000,
      )
        .then(async (res) => {
          const t = await res.text();
          carrierSettled = true;
          log("ff-block ->", sid, res.status, "bytes=" + t.length, "attempt=" + attempt, entry.qid);
          if (!res.ok) throw new Error("opencode HTTP " + res.status);
          return true;
        })
        .catch((e) => {
          carrierSettled = true;
          carrierErr = e.message || String(e);
          return false;
        });
      pendingCarrier = carrier;
      // Poll for admission while the POST lives. If it outlives the window,
      // await it instead — never fire a second POST alongside a live one.
      const deadline = Date.now() + ADMISSION_WAIT_MS;
      let admitted = false;
      for (;;) {
        admitted = await messageAdmitted(entry);
        if (admitted || carrierSettled) break;
        if (Date.now() >= deadline) {
          log("wake-", entry.qid, sid, "admission window over, awaiting live POST");
          await carrier;
          admitted = await messageAdmitted(entry);
          break;
        }
        await sleep(ADMISSION_POLL_MS);
      }
      // The POST is over (or just resolved): give the store a moment to land
      // the message write before deciding — long after a 2xx (the store may
      // lag the response), a couple of polls after a rejection.
      const graceRounds = carrierErr === null ? 15 : 3;
      for (let i = 0; i < graceRounds && !admitted; i++) {
        if (!carrierSettled) break;
        await sleep(2000);
        admitted = await messageAdmitted(entry);
      }
      if (admitted) {
        entry.state = "done";
        entry.error = null;
        saveQueue();
        log("wake-", entry.qid, sid, "admitted", "attempt=" + attempt);
        return;
      }
      if (carrierErr === null) {
        // HTTP 2xx without a store match: the turn ran to completion, that IS
        // delivery — never retry a completed turn.
        entry.state = "done";
        entry.error = null;
        saveQueue();
        log("wake-", entry.qid, sid, "completed without store match (kept)", "attempt=" + attempt);
        return;
      }
      entry.error = carrierErr;
      entry.state = attempt < MAX_DELIVERY_ATTEMPTS ? "queued" : "failed";
      saveQueue();
      log("wake-", entry.qid, sid, "attempt", attempt, "failed:", carrierErr);
      if (attempt < MAX_DELIVERY_ATTEMPTS) await sleep(attempt === 1 ? 1000 : 3000);
    }
  } catch (e) {
    entry.state = "failed";
    entry.error = e.message || String(e);
    saveQueue();
    log("wake-", entry.qid, sid, "delivery error:", e.message || String(e));
  } finally {
    const release = () => {
      const left = Math.max(0, (activeBlockTurns.get(sid) || 0) - 1);
      if (left > 0) activeBlockTurns.set(sid, left);
      else activeBlockTurns.delete(sid);
      // the blocking POST returns only when the turn is over
      lastMessageEvAt.delete(sid);
    };
    // admission can be seen while the POST (the turn) is still running — hold
    // the in-flight flag until the carrier actually settles, like before
    if (pendingCarrier) pendingCarrier.then(release, release);
    else release();
  }
}

// ---- v2 -> legacy question translation ------------------------------------
// The legacy /question routes are the only working question API on opencode
// 1.19.x serve; v2 session-scoped routes silently lose scoping. The legacy
// reply/reject additionally need ?directory= of the session.

const sessionDirs = new Map(); // sessionID -> directory (forward-slash form)

async function sessionDirectory(sid) {
  if (sessionDirs.has(sid)) return sessionDirs.get(sid);
  try {
    const res = await opencodeFetch(`/session/${sid}`, { method: "GET" }, 15000);
    if (res.ok) {
      const j = await res.json();
      const d = (j && (j.directory || (j.location && j.location.directory))) || "";
      if (d) {
        sessionDirs.set(sid, d);
        return d;
      }
    }
  } catch (e) {
    log("sessionDirectory err:", sid, e.message || String(e));
  }
  return "";
}

async function handleQuestionList(id, sid, started) {
  try {
    const res = await opencodeFetch("/question", { method: "GET" }, 15000);
    const text = await res.text();
    let list = [];
    try {
      const j = JSON.parse(text);
      list = Array.isArray(j) ? j : Array.isArray(j && j.data) ? j.data : [];
    } catch {
      list = [];
    }
    const mine = list.filter((q) => q && q.sessionID === sid);
    wsSend({ t: "res", id, s: 200, b: { data: mine } });
    log("res ->", id, res.status, "question-list", mine.length, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 502, b: { message: "question list failed: " + (e.message || String(e)) } });
    log("res ->", id, 502, "question list error:", e.message, "ms=" + (Date.now() - started));
  }
}

async function handleQuestionAction(id, sid, rid, action, body, started) {
  try {
    const dir = await sessionDirectory(sid);
    const qs = dir ? "?directory=" + encodeURIComponent(dir) : "";
    const res = await opencodeFetch(`/question/${rid}/${action}${qs}`, {
      method: "POST",
      ...(body !== undefined && body !== null ? { body: typeof body === "string" ? body : JSON.stringify(body) } : {}),
    }, 15000);
    const text = await res.text();
    let parsed = null;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      parsed = { raw: truncate(text) };
    }
    wsSend({ t: "res", id, s: res.status, b: parsed });
    log("res ->", id, res.status, "question-" + action, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 502, b: { message: "question " + action + " failed: " + (e.message || String(e)) } });
    log("res ->", id, 502, "question " + action + " error:", e.message, "ms=" + (Date.now() - started));
  }
}

async function handleSessionStatus(id, started) {
  try {
    const res = await opencodeFetch("/session/status", { method: "GET" }, 30000);
    const text = await res.text();
    let st = null;
    try {
      st = text ? JSON.parse(text) : null;
    } catch {
      st = null;
    }
    if (!st || typeof st !== "object" || Array.isArray(st)) st = {};
    const now = Date.now();
    const sids = new Set([...activeBlockTurns.keys(), ...lastMessageEvAt.keys()]);
    let added = 0;
    for (const sid of sids) {
      if (!inFlight(sid)) {
        // forget sessions that went quiet a long time ago
        const at = lastMessageEvAt.get(sid);
        if (at !== undefined && now - at > 600000) lastMessageEvAt.delete(sid);
        continue;
      }
      const cur = st[sid];
      const type = cur && typeof cur === "object" && cur.type;
      if (type !== "busy" && type !== "retry") {
        st[sid] = { type: "busy" };
        added++;
      }
    }
    // surface the durable prompt queue: everything not delivered (or failed)
    // rides along so the app can see pending retries and delivery errors
    const qBySid = new Map();
    for (const e of promptQueue.values()) {
      if (e.state === "done") continue;
      if (!qBySid.has(e.sid)) qBySid.set(e.sid, []);
      qBySid.get(e.sid).push({
        qid: e.qid,
        state: e.state,
        ts: e.ts,
        attempts: e.attempts || 0,
        error: e.error ? String(e.error).slice(0, 200) : null,
      });
    }
    for (const [sid, items] of qBySid) {
      const cur = st[sid];
      const base = cur && typeof cur === "object" && !Array.isArray(cur) ? cur : {};
      const busy = items.some((i) => i.state === "queued" || i.state === "sending");
      st[sid] = {
        ...base,
        ...(busy && base.type !== "busy" && base.type !== "retry" ? { type: "busy" } : {}),
        promptQueue: items,
      };
    }
    wsSend({ t: "res", id, s: res.status, b: st });
    log("res ->", id, res.status, "status sessions=" + Object.keys(st).length, "inFlight=+" + added, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 502, b: { message: "session status failed: " + (e.message || String(e)) } });
    log("res ->", id, 502, "session status error:", e.message, "ms=" + (Date.now() - started));
  }
}

// ---- file browsing (unjailed) ----------------------------------------------
// Served straight from the PC filesystem: opencode's /file refuses paths
// outside the session workdir, the app needs the whole disk (~ = home dir).

const FILE_PREVIEW_MAX = 2 * 1024 * 1024;

function resolveFsAbs(raw) {
  const dir = String(raw || "");
  if (dir === "~") return os.homedir();
  if (dir.startsWith("~/") || dir.startsWith("~\\")) return path.join(os.homedir(), dir.slice(2));
  return dir;
}

function resolveFsPath(p) {
  const q = new URLSearchParams(p.split("?")[1] || "");
  return resolveFsAbs(q.get("path") || "");
}

async function handleFileList(id, p, started) {
  const dir = resolveFsPath(p);
  if (!dir) {
    wsSend({ t: "res", id, s: 400, b: { message: "path is required" } });
    return;
  }
  try {
    const entries = await fs.promises.readdir(dir, { withFileTypes: true });
    const nodes = await Promise.all(
      entries.map(async (e) => {
        let size = 0;
        let modified = 0;
        try {
          const st = await fs.promises.stat(path.join(dir, e.name));
          size = st.size;
          modified = Math.floor(st.mtimeMs);
        } catch {
          /* race: entry vanished between readdir and stat */
        }
        return {
          name: e.name,
          path: dir,
          absolute: path.join(dir, e.name),
          type: e.isDirectory() ? "directory" : "file",
          size,
          modified,
        };
      }),
    );
    wsSend({ t: "res", id, s: 200, b: nodes });
    log("res ->", id, 200, "file-list", nodes.length, dir, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 500, b: { message: e.message || String(e) } });
    log("res ->", id, 500, "file-list error:", e.message, "ms=" + (Date.now() - started));
  }
}

async function handleFileContent(id, p, started) {
  const file = resolveFsPath(p);
  if (!file) {
    wsSend({ t: "res", id, s: 400, b: { message: "path is required" } });
    return;
  }
  try {
    const st = await fs.promises.stat(file);
    if (st.size > FILE_PREVIEW_MAX) {
      wsSend({ t: "res", id, s: 200, b: { type: "binary", content: null } });
      log("res ->", id, 200, "file-content too-big", st.size, file);
      return;
    }
    const buf = await fs.promises.readFile(file);
    const bin = buf.includes(0);
    wsSend({
      t: "res",
      id,
      s: 200,
      b: { type: bin ? "binary" : "text", content: bin ? null : buf.toString("utf8") },
    });
    log("res ->", id, 200, "file-content", bin ? "binary" : "bytes=" + buf.length, file, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 500, b: { message: e.message || String(e) } });
    log("res ->", id, 500, "file-content error:", e.message, "ms=" + (Date.now() - started));
  }
}

// ---- file operations (unjailed; rm goes only to the recycle bin) ----------
// The phone gets a minimal unix-ish toolbox: mv/ren, cp, rm and chunked
// download. Hard rules: NTFS ACLs are NEVER touched (no chmod, no chown, no
// takeown — nothing permission-shaped ever runs), and `rm` never destroys
// anything: the target is handed to the Windows Recycle Bin through the shell
// recycle API (PowerShell + Microsoft.VisualBasic), so every deletion stays
// restorable on the PC. If the recycle API refuses (locked file, UNC path),
// the request fails loudly instead of falling back to a permanent delete.

const DOWNLOAD_CHUNK = 64 * 1024;

function psLiteral(s) {
  return "'" + String(s).replace(/'/g, "''") + "'";
}

function runPowerShell(script, timeoutMs) {
  return new Promise((resolve, reject) => {
    const exe = path.join(
      process.env.SystemRoot || "C:\\Windows",
      "System32",
      "WindowsPowerShell",
      "v1.0",
      "powershell.exe",
    );
    const child = spawn(
      exe,
      [
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-WindowStyle",
        "Hidden",
        "-EncodedCommand",
        Buffer.from(script, "utf16le").toString("base64"),
      ],
      { windowsHide: true, stdio: ["ignore", "pipe", "pipe"] },
    );
    let out = "";
    let err = "";
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try {
        child.kill();
      } catch {
        /* already gone */
      }
      reject(new Error("powershell timeout after " + timeoutMs + "ms"));
    }, timeoutMs);
    child.stdout.on("data", (d) => {
      out += d;
    });
    child.stderr.on("data", (d) => {
      err += d;
    });
    child.on("error", (e) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(e);
    });
    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0) resolve(out);
      else {
        reject(
          new Error((err || out || "powershell exited with code " + code).trim().slice(0, 500)),
        );
      }
    });
  });
}

async function targetExists(p) {
  try {
    await fs.promises.access(p);
    return true;
  } catch {
    return false;
  }
}

async function handleFileTransfer(id, body, started, isCopy) {
  const from = path.normalize(resolveFsAbs(String((body && body.from) || "")));
  const to = path.normalize(resolveFsAbs(String((body && body.to) || "")));
  const kind = isCopy ? "copy" : "move";
  if (!from || from === "." || !to || to === ".") {
    wsSend({ t: "res", id, s: 400, b: { message: "from and to are required" } });
    return;
  }
  if (from === to) {
    wsSend({ t: "res", id, s: 400, b: { message: "source and destination are the same" } });
    return;
  }
  if (to.startsWith(from + path.sep)) {
    wsSend({ t: "res", id, s: 400, b: { message: "destination is inside the source" } });
    return;
  }
  try {
    if (!(await targetExists(from))) {
      wsSend({ t: "res", id, s: 404, b: { message: "source not found: " + from } });
      return;
    }
    if (await targetExists(to)) {
      wsSend({ t: "res", id, s: 409, b: { message: "destination already exists: " + to } });
      return;
    }
    if (isCopy) {
      await fs.promises.cp(from, to, { recursive: true, force: false, errorOnExist: true });
    } else {
      try {
        await fs.promises.rename(from, to);
      } catch (e) {
        if (e && e.code === "EXDEV") {
          // cross-volume move: copy first, then drop the source — the data
          // exists at the destination, nothing is lost
          await fs.promises.cp(from, to, { recursive: true, force: false, errorOnExist: true });
          await fs.promises.rm(from, { recursive: true });
        } else {
          throw e;
        }
      }
    }
    wsSend({ t: "res", id, s: 200, b: { ok: true, op: kind } });
    log("res ->", id, 200, "file-" + kind, from, "->", to, "ms=" + (Date.now() - started));
  } catch (e) {
    const code = e && e.code === "EEXIST" ? 409 : 500;
    wsSend({ t: "res", id, s: code, b: { message: e.message || String(e) } });
    log("res ->", id, code, "file-" + kind + " error:", e.message, "ms=" + (Date.now() - started));
  }
}

async function handleFileDelete(id, body, started) {
  const target = path.normalize(resolveFsAbs(String((body && body.path) || "")));
  if (!target || target === ".") {
    wsSend({ t: "res", id, s: 400, b: { message: "path is required" } });
    return;
  }
  if (path.resolve(target) === path.parse(path.resolve(target)).root) {
    wsSend({ t: "res", id, s: 400, b: { message: "refusing to delete a drive root" } });
    return;
  }
  try {
    const st = await fs.promises.stat(target);
    const fn = st.isDirectory() ? "DeleteDirectory" : "DeleteFile";
    const script =
      "$ErrorActionPreference='Stop';" +
      "Add-Type -AssemblyName Microsoft.VisualBasic;" +
      "[Microsoft.VisualBasic.FileIO.FileSystem]::" +
      fn +
      "(" +
      psLiteral(target) +
      ",'OnlyErrorDialogs','SendToRecycleBin')";
    await runPowerShell(script, 180000);
    wsSend({ t: "res", id, s: 200, b: { ok: true, method: "recycle-bin" } });
    log("res ->", id, 200, "file-delete (recycle bin)", target, "ms=" + (Date.now() - started));
  } catch (e) {
    const code = e && e.code === "ENOENT" ? 404 : 500;
    wsSend({ t: "res", id, s: code, b: { message: e.message || String(e) } });
    log("res ->", id, code, "file-delete error:", e.message, "ms=" + (Date.now() - started));
  }
}

async function handleFileMkdir(id, body, started) {
  const target = path.normalize(resolveFsAbs(String((body && body.path) || "")));
  if (!target || target === ".") {
    wsSend({ t: "res", id, s: 400, b: { message: "path is required" } });
    return;
  }
  if (path.resolve(target) === path.parse(path.resolve(target)).root) {
    wsSend({ t: "res", id, s: 400, b: { message: "refusing to touch a drive root" } });
    return;
  }
  try {
    // single level only — creating a whole chain hides typos in the picker
    await fs.promises.mkdir(target, { recursive: false });
    wsSend({ t: "res", id, s: 200, b: { ok: true, path: target } });
    log("res ->", id, 200, "file-mkdir", target, "ms=" + (Date.now() - started));
  } catch (e) {
    const code = e && e.code === "EEXIST" ? 409 : e && e.code === "ENOENT" ? 404 : 500;
    wsSend({ t: "res", id, s: code, b: { message: e.message || String(e) } });
    log("res ->", id, code, "file-mkdir error:", e.message, "ms=" + (Date.now() - started));
  }
}

async function handleFileRaw(id, p, started) {
  const file = path.normalize(resolveFsPath(p));
  if (!file || file === ".") {
    wsSend({ t: "res", id, s: 400, b: { message: "path is required" } });
    return;
  }
  const q = new URLSearchParams(p.split("?")[1] || "");
  const offset = Math.max(parseInt(q.get("offset") || "0", 10) || 0, 0);
  const len = Math.min(
    Math.max(parseInt(q.get("len") || String(DOWNLOAD_CHUNK), 10) || DOWNLOAD_CHUNK, 1),
    DOWNLOAD_CHUNK,
  );
  try {
    const st = await fs.promises.stat(file);
    if (st.isDirectory()) {
      wsSend({ t: "res", id, s: 400, b: { message: "not a file: " + file } });
      return;
    }
    const name = path.basename(file);
    if (offset >= st.size) {
      wsSend({ t: "res", id, s: 200, b: { name, total: st.size, offset, data: "", eof: true } });
      return;
    }
    const fh = await fs.promises.open(file, "r");
    let read = 0;
    let buf = null;
    try {
      buf = Buffer.alloc(len);
      ({ bytesRead: read } = await fh.read(buf, 0, len, offset));
    } finally {
      await fh.close();
    }
    wsSend({
      t: "res",
      id,
      s: 200,
      b: {
        name,
        total: st.size,
        offset,
        data: buf.subarray(0, read).toString("base64"),
        eof: offset + read >= st.size,
      },
    });
    log(
      "res ->",
      id,
      200,
      "file-raw",
      offset + "/" + st.size,
      "read=" + read,
      "ms=" + (Date.now() - started),
    );
  } catch (e) {
    const code = e && e.code === "ENOENT" ? 404 : 500;
    wsSend({ t: "res", id, s: code, b: { message: e.message || String(e) } });
    log("res ->", id, code, "file-raw error:", e.message, "ms=" + (Date.now() - started));
  }
}

// ---- lazy message loading ---------------------------------------------
// opencode 1.19.3 has no working `before` cursor for message lists, so the
// agent fetches the full list locally (fast, no relay cost) and serves
// tail slices to the app. Cached briefly, invalidated on new message events.
const msgCache = new Map(); // sessionID -> { at, list }

async function fetchSessionMessages(sessionID) {
  const cached = msgCache.get(sessionID);
  const now = Date.now();
  if (cached && now - cached.at < 8000) return cached.list;
  const res = await opencodeFetch(`/session/${sessionID}/message?limit=100000`, { method: "GET" }, 60000);
  if (!res.ok) throw new Error("opencode HTTP " + res.status);
  const text = await res.text();
  let list = [];
  try {
    const j = JSON.parse(text);
    list = Array.isArray(j) ? j : [];
  } catch {
    list = [];
  }
  msgCache.set(sessionID, { at: now, list });
  return list;
}

const msgChars = (m) =>
  Array.isArray(m && m.parts) ? m.parts.reduce((a, p) => a + (p.text ? p.text.length : 0), 0) : 0;

// Cut a message so that only its LAST `keep` characters of text remain.
// Part ids are preserved so the full message can be merged back later.
function truncateMessage(m, keep) {
  const parts = Array.isArray(m && m.parts) ? m.parts : [];
  let total = 0;
  for (const p of parts) total += p.text ? p.text.length : 0;
  if (total <= keep) return m;
  let drop = total - keep;
  const out = [];
  for (const p of parts) {
    const len = p.text ? p.text.length : 0;
    if (len === 0) {
      out.push(p);
      continue;
    }
    if (drop >= len) {
      drop -= len;
      continue;
    }
    out.push({ ...p, text: p.text.slice(drop) });
    drop = 0;
  }
  return { ...m, parts: out, truncated: true, fullChars: total };
}

function sliceMessages(list, q) {
  let end = list.length;
  const before = q.get("before") || "";
  if (before) {
    const i = list.findIndex((m) => m.info && m.info.id === before);
    if (i < 0) return { messages: [], hasMore: false, oldest: "" };
    end = i + 1; // inclusive: the full version of a truncated message comes back here
  }
  const limit = Math.min(Math.max(parseInt(q.get("limit") || "25", 10) || 25, 1), 500);
  const chars = Math.max(parseInt(q.get("chars") || "0", 10) || 0, 0);
  let start = Math.max(0, end - limit);
  let truncIdx = -1;
  let truncKeep = 0;
  if (chars > 0) {
    let acc = 0;
    start = end;
    while (start > 0 && acc < chars && end - start < 500) {
      const m = list[start - 1];
      const len = msgChars(m);
      const remaining = chars - acc;
      if (len <= remaining) {
        acc += len;
        start -= 1;
      } else {
        truncIdx = start - 1;
        truncKeep = remaining;
        start -= 1;
        acc += remaining;
        break;
      }
    }
  }
  let slice = list.slice(start, end);
  if (truncIdx >= 0 && truncKeep > 0) {
    slice = slice.slice();
    slice[truncIdx - start] = truncateMessage(list[truncIdx], truncKeep);
  }
  return {
    messages: slice,
    hasMore: start > 0,
    oldest: slice.length > 0 ? (slice[0].info && slice[0].info.id) || "" : "",
  };
}

async function handleMessagesTail(id, method, p, sessionID) {
  const started = Date.now();
  if (method !== "GET") {
    wsSend({ t: "res", id, s: 405, b: { message: "method not allowed" } });
    return;
  }
  const q = new URLSearchParams(p.split("?")[1] || "");
  try {
    const list = await fetchSessionMessages(sessionID);
    const out = sliceMessages(list, q);
    out.inFlight = inFlight(sessionID);
    const outBytes = JSON.stringify(out).length;
    wsSend({ t: "res", id, s: 200, b: out });
    log("tail ->", id, sessionID, "msgs=" + out.messages.length, "hasMore=" + out.hasMore, "inFlight=" + out.inFlight, "bytes=" + outBytes, "ms=" + (Date.now() - started));
  } catch (e) {
    wsSend({ t: "res", id, s: 502, b: { message: "messages-tail failed: " + (e.message || String(e)) } });
    log("tail ->", id, 502, e.message);
  }
}

// ---- SSE: shared batching across all event subscriptions ----
const FLUSH_MS = 250;
const SEEN_CAP = 8000;
// forward only the event types the app actually handles (drops deltas,
// heartbeats, diffs and everything else opencode emits)
const EV_FORWARD = new Set([
  "message.updated",
  "message.part.updated",
  "message.part.removed",
  "session.created",
  "session.updated",
  "session.deleted",
  "session.status",
  "session.error",
  "permission.asked",
  "permission.replied",
  "question.asked",
  "question.replied",
  "question.rejected",
]);

let evBuffer = [];
let evSeen = new Set();
let flushTimer = null;
const seenEvIds = new Set();
const seenEvIdsOrder = [];

const keyOf = (ev) => {
  const props = ev.properties || ev.data;
  if (ev.type === "message.part.updated" && props && props.part) return "part:" + props.part.id;
  if (ev.type === "message.updated" && props && props.info) return "msg:" + props.info.id;
  if ((ev.type === "session.updated" || ev.type === "session.created") && props && props.info)
    return "sess:" + props.info.id + ":" + ev.type;
  if (ev.type === "session.status" && props) return "stat:" + (props.sessionID || "");
  return null;
};

function flushEvents() {
  flushTimer = null;
  if (evBuffer.length === 0) return;
  const batch = evBuffer;
  evBuffer = [];
  evSeen = new Set();
  wsSend({ t: "ev", d: batch });
}

function enqueue(ev) {
  if (!ev || typeof ev.type !== "string") return;
  const props = ev.properties || {};
  const sid = props.sessionID;
  if (sid && (ev.type === "message.updated" || ev.type === "message.part.updated" || ev.type === "message.part.removed")) {
    msgCache.delete(sid);
    // live-turn activity: completed messages mark the end of a turn, everything
    // else (part deltas, created/unfinished messages) marks it as running
    if (ev.type === "message.updated") {
      const info = props.info;
      const done = !!(info && info.time && info.time.completed != null);
      if (done) lastMessageEvAt.delete(sid);
      else lastMessageEvAt.set(sid, Date.now());
    } else {
      lastMessageEvAt.set(sid, Date.now());
    }
  }
  if (!EV_FORWARD.has(ev.type)) return;
  if (ev.id) {
    if (seenEvIds.has(ev.id)) return;
    seenEvIds.add(ev.id);
    seenEvIdsOrder.push(ev.id);
    if (seenEvIdsOrder.length > SEEN_CAP) {
      for (const old of seenEvIdsOrder.splice(0, 1500)) seenEvIds.delete(old);
    }
  }
  const key = keyOf(ev);
  if (key) {
    if (evSeen.has(key)) {
      // last-writer-wins: drop the stale copy, keep position at the tail
      evBuffer = evBuffer.filter((e) => keyOf(e) !== key);
    }
    evSeen.add(key);
  }
  if (ev.type === "session.created" || ev.type === "session.updated") {
    const info = (ev.properties || {}).info;
    const dir = info && (info.directory || (info.location && info.location.directory));
    if (dir) ensureSub(dir);
  }
  evBuffer.push(ev);
  if (!flushTimer) flushTimer = setTimeout(flushEvents, FLUSH_MS);
}

// ---- SSE subscriptions ----
// opencode scopes /event by ?directory=, and a single stream can silently go
// stale (no retry). Keep one base stream plus one per session directory; every
// stream reconnects forever. Duplicates are dropped by event id above.
const sseSubs = new Map(); // key: "" (base) | directory -> AbortController

function ensureSub(dir) {
  if (!dir || typeof dir !== "string") return;
  if (sseSubs.has(dir)) return;
  startSub(dir);
}

function startSub(dir) {
  const key = dir || "";
  if (sseSubs.has(key)) return;
  const ctrl = new AbortController();
  sseSubs.set(key, ctrl);
  const label = dir || "(default)";
  (async () => {
    let attempt = 0;
    while (shouldRun && sseSubs.get(key) === ctrl && !ctrl.signal.aborted) {
      try {
        const headers = {};
        if (authHeader) headers["authorization"] = authHeader;
        const url = ocUrl + "/event" + (dir ? "?directory=" + encodeURIComponent(dir) : "");
        const res = await fetch(url, { headers, signal: ctrl.signal });
        if (!res.ok || !res.body) throw new Error("HTTP " + res.status);
        log("SSE connected:", label);
        attempt = 0;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = "";
        for (;;) {
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
              enqueue(JSON.parse(data));
            } catch {
              /* skip malformed event */
            }
          }
        }
        throw new Error("stream ended");
      } catch (e) {
        if (ctrl.signal.aborted || sseSubs.get(key) !== ctrl) return;
        attempt += 1;
        const wait = Math.min(3000 * attempt, 15000);
        log("SSE drop (" + label + "), retry in " + wait + "ms:", e.message);
        await new Promise((r) => setTimeout(r, wait));
      }
    }
  })();
}

async function seedSubs() {
  try {
    const res = await opencodeFetch("/api/session?limit=500&order=desc", { method: "GET" }, 30000);
    if (!res.ok) {
      log("SSE seed HTTP", res.status);
      return;
    }
    const j = await res.json();
    const list = Array.isArray(j) ? j : Array.isArray(j && j.data) ? j.data : [];
    const dirs = new Set();
    for (const s of list) {
      if (!s || typeof s !== "object") continue;
      const d = s.directory || (s.location && s.location.directory) || (s.path && s.path.cwd);
      if (typeof d === "string" && d) dirs.add(d);
    }
    for (const d of dirs) ensureSub(d);
    log("SSE seeded directory streams:", dirs.size);
  } catch (e) {
    log("SSE seed error:", e.message);
  }
}

function collectSessionDirs(obj) {
  const dirs = new Set();
  const scan = (o) => {
    if (!o || typeof o !== "object") return;
    if (Array.isArray(o)) {
      o.forEach(scan);
      return;
    }
    const d = o.directory || (o.location && o.location.directory);
    if (typeof d === "string" && d) dirs.add(d);
    if (typeof o.id === "string" && o.id.startsWith("ses_") && typeof d === "string" && d) {
      sessionDirs.set(o.id, d);
    }
    if (Array.isArray(o.data)) o.data.forEach(scan);
  };
  scan(obj);
  for (const d of dirs) ensureSub(d);
}

function startSse() {
  for (const ctrl of sseSubs.values()) ctrl.abort();
  sseSubs.clear();
  evBuffer = [];
  evSeen = new Set();
  if (flushTimer) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  startSub(null);
  seedSubs();
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
    lastInAt = Date.now();
    wsSend({ t: "presence" });
    startSse();
  });

  ws.addEventListener("message", async (event) => {
    lastInAt = Date.now();
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
  if (lastInAt > 0 && Date.now() - lastInAt > STALE_MS) {
    log("heartbeat: no inbound for", Math.round((Date.now() - lastInAt) / 1000) + "s, forcing reconnect");
    try {
      ws.close();
    } catch {
      /* ignore */
    }
    scheduleReconnect();
    return;
  }
  try {
    ws.send(JSON.stringify({ t: "ping" }));
  } catch (e) {
    log("heartbeat send failed:", e.message);
    scheduleReconnect();
  }
}, 45000);

function scheduleReconnect() {
  if (!shouldRun) return;
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectRelay();
  }, backoff);
  backoff = Math.min(backoff * 2, 15000);
}

// serve watchdog: relay and SSE reconnect on their own, but `opencode serve`
// dying (crash, reboot leftovers, hibernation resume gone wrong) is only
// detected here — revive it after two failed health checks a minute apart.
let serveFails = 0;
setInterval(async () => {
  if (!shouldRun || !autoStartServe) return;
  try {
    if (await healthy()) {
      serveFails = 0;
      return;
    }
  } catch {
    /* treat as unhealthy */
  }
  if (++serveFails < 2) return;
  serveFails = 0;
  log("watchdog: opencode serve unhealthy, restarting");
  if (serveProcess) {
    try {
      if (serveProcess.pid) {
        spawn("taskkill", ["/pid", String(serveProcess.pid), "/t", "/f"], { stdio: "ignore" });
      } else {
        serveProcess.kill();
      }
    } catch {
      /* ignore */
    }
    serveProcess = null;
  }
  await ensureServer().catch((e) => log("ensureServer error:", (e && e.message) || e));
}, 60000);

async function main() {
  log("OpenCode Mobile bridge starting");
  log("relay:", relayUrl);
  log("opencode:", ocUrl);
  if (directory) log("directory:", directory);
  await ensureServer().catch((e) => log("ensureServer error:", (e && e.message) || e));
  loadQueue(); // receipts from a previous process
  recoverQueue(); // re-deliver wakes that never reached the store
  connectRelay();
}

process.on("SIGINT", () => {
  shouldRun = false;
  for (const ctrl of sseSubs.values()) ctrl.abort();
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
