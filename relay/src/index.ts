export interface Env {
  RELAY: DurableObjectNamespace;
  PAIRING_TOKEN: string;
}

const CLIENT_TAG = "client";
const AGENT_TAG = "agent";

function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  if (ab.length !== bb.length) {
    let diff = 0;
    for (let i = 0; i < Math.max(ab.length, bb.length); i++) {
      diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
    }
    return diff === 0;
  }
  let out = 0;
  for (let i = 0; i < ab.length; i++) out |= ab[i] ^ bb[i];
  return out === 0;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response(JSON.stringify({ ok: true }), {
        headers: { "content-type": "application/json" },
      });
    }

    if (url.pathname !== "/ws") {
      if (url.pathname === "/" || url.pathname === "") {
        return new Response(
          JSON.stringify({
            error: "websocket endpoint is /ws",
            hint: "connect with ?role=client&token=<PAIRING_TOKEN> or ?role=agent&token=<PAIRING_TOKEN>",
            health: "/health",
          }),
          { status: 200, headers: { "content-type": "application/json" } },
        );
      }
      return new Response("not found (websocket endpoint is /ws)", { status: 404 });
    }

    const token = url.searchParams.get("token") ?? "";
    const role = url.searchParams.get("role") ?? "client";
    const tag = role === "agent" ? AGENT_TAG : CLIENT_TAG;

    if (!env.PAIRING_TOKEN || !timingSafeEqual(token, env.PAIRING_TOKEN)) {
      return new Response("unauthorized", { status: 401 });
    }

    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket upgrade", { status: 426 });
    }

    const id = env.RELAY.idFromName("relay");
    const stub = env.RELAY.get(id);
    const upstream = new Request(`https://do/ws?tag=${tag}`, {
      headers: { Upgrade: "websocket" },
    });
    return stub.fetch(upstream);
  },
};

export class RelayRoom implements DurableObject {
  constructor(private readonly state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const tag = url.searchParams.get("tag") === AGENT_TAG ? AGENT_TAG : CLIENT_TAG;

    const pair = new WebSocketPair();
    const server = pair[0];
    const client = pair[1];

    server.serializeAttachment({ tag });
    this.state.acceptWebSocket(server);

    if (tag === CLIENT_TAG) {
      server.send(JSON.stringify({ t: "presence", agents: this.agentCount() }));
    } else {
      this.broadcastPresence();
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  tagOf(ws: WebSocket): string {
    try {
      const att = ws.deserializeAttachment() as { tag?: string } | null;
      if (att && att.tag === AGENT_TAG) return AGENT_TAG;
    } catch {
      /* fall through */
    }
    return CLIENT_TAG;
  }

  agentCount(): number {
    return this.state.getWebSockets().filter((ws) => this.tagOf(ws) === AGENT_TAG).length;
  }

  clientSockets(): WebSocket[] {
    return this.state.getWebSockets().filter((ws) => this.tagOf(ws) === CLIENT_TAG);
  }

  agentSockets(): WebSocket[] {
    return this.state.getWebSockets().filter((ws) => this.tagOf(ws) === AGENT_TAG);
  }

  broadcast(obj: unknown) {
    const payload = JSON.stringify(obj);
    for (const ws of this.clientSockets()) {
      try {
        ws.send(payload);
      } catch {
        /* socket dying, ignore */
      }
    }
  }

  broadcastPresence() {
    this.broadcast({ t: "presence", agents: this.agentCount() });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    const text = typeof message === "string" ? message : new TextDecoder().decode(message);
    console.log(`relay: msg from ${this.tagOf(ws)} chars=${text.length}`);
    let obj: Record<string, unknown>;
    try {
      obj = JSON.parse(text) as Record<string, unknown>;
    } catch {
      return;
    }

    if (this.tagOf(ws) === AGENT_TAG) {
      await this.handleAgentMessage(ws, obj);
    } else {
      await this.handleClientMessage(obj);
    }
  }

  private async handleAgentMessage(ws: WebSocket, obj: Record<string, unknown>) {
    switch (obj["t"]) {
      case "res": {
        const payload = JSON.stringify(obj);
        for (const client of this.clientSockets()) {
          try {
            client.send(payload);
          } catch (e) {
            console.log(
              `relay: send res to client failed id=${String(obj["id"])} chars=${payload.length} err=${String(e)}`,
            );
          }
        }
        console.log(`relay: res id=${String(obj["id"])} chars=${payload.length} clients=${this.clientSockets().length}`);
        break;
      }
      case "ev":
        this.broadcast({ t: "ev", d: obj["d"] });
        break;
      case "presence":
        this.broadcastPresence();
        break;
      case "ping":
        try {
          ws.send(JSON.stringify({ t: "pong" }));
        } catch {
          /* socket dying, ignore */
        }
        break;
      case "pong":
        break;
      default:
        break;
    }
  }

  private async handleClientMessage(obj: Record<string, unknown>) {
    switch (obj["t"]) {
      case "req": {
        const agents = this.agentSockets();
        if (agents.length === 0) {
          this.broadcast({
            t: "res",
            id: obj["id"],
            s: 503,
            b: { message: "PC agent offline" },
          });
          return;
        }
        const payload = JSON.stringify(obj);
        try {
          agents[0].send(payload);
        } catch {
          this.broadcast({ t: "res", id: obj["id"], s: 503, b: { message: "PC agent offline" } });
        }
        break;
      }
      case "presence":
        this.broadcastPresence();
        break;
      case "ping":
        this.broadcastPresence();
        break;
      default:
        break;
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string, _wasClean: boolean) {
    this.handleLeave(ws);
  }

  async webSocketError(ws: WebSocket, _error: unknown) {
    this.handleLeave(ws);
  }

  private handleLeave(ws: WebSocket) {
    const wasAgent = this.tagOf(ws) === AGENT_TAG;
    try {
      ws.close(1000, "bye");
    } catch {
      /* already closed */
    }
    if (wasAgent) {
      this.broadcastPresence();
    }
  }
}
