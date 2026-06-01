# Cloudflare Worker setup

Cloudflare Worker route is an optional WebSocket upstream route for TgWsProxy Android. The Android app connects to:

```text
wss://<worker-domain>/apiws?dst=<telegram-dc-ip>&dc=<dc-id>&media=<0-or-1>
```

In the app, paste only the Worker hostname, without `https://`, `wss://`, or `/apiws`:

```text
example.username.workers.dev
```

Create your own Worker. Do not use random public Worker domains from other people.

## When To Use

- Use Worker route when Direct WebSocket or CF Proxy is unstable on your network.
- For mobile networks, a practical preset is Mobile policy -> Worker first.
- If Worker diagnostics fail, check the Worker domain first, then try CF Proxy or Direct routes.

## Setup

1. Open [Cloudflare Dashboard](https://dash.cloudflare.com).
2. Go to **Workers & Pages**.
3. Create a Worker.
4. Open **Edit code**.
5. Paste the Worker code from the section below.
6. Deploy the Worker.
7. Copy the hostname, for example `example.username.workers.dev`.
8. In TgWsProxy Android, open **Settings** -> **Cloudflare Worker** and paste the hostname.
9. Run Worker diagnostics or the active route connection test.

## Worker Code

```javascript
import { connect } from "cloudflare:sockets";

function toBytes(data) {
    if (data instanceof ArrayBuffer) {
        return new Uint8Array(data);
    }
    if (typeof data === "string") {
        return new TextEncoder().encode(data);
    }
    if (data && typeof data.arrayBuffer === "function") {
        return data.arrayBuffer().then((ab) => new Uint8Array(ab));
    }
    return new Uint8Array();
}

export default {
    async fetch(request) {
        if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
            return new Response("Expected websocket", { status: 426 });
        }

        const url = new URL(request.url);
        if (url.pathname !== "/apiws") {
            return new Response("Not found", { status: 404 });
        }

        const dst = url.searchParams.get("dst");
        if (!dst) {
            return new Response("Missing dst", { status: 400 });
        }

        const pair = new WebSocketPair();
        const client = pair[0];
        const server = pair[1];
        server.accept();

        const socket = connect({ hostname: dst, port: 443 });
        const tcpReader = socket.readable.getReader();
        const tcpWriter = socket.writable.getWriter();

        server.addEventListener("message", async (event) => {
            try {
                await tcpWriter.write(await toBytes(event.data));
            } catch {
                try {
                    server.close(1011, "tcp write failed");
                } catch {}
            }
        });

        server.addEventListener("close", async () => {
            try {
                await tcpWriter.close();
            } catch {}
            try {
                socket.close();
            } catch {}
        });

        (async () => {
            try {
                while (true) {
                    const { value, done } = await tcpReader.read();
                    if (done) {
                        break;
                    }
                    if (value) {
                        server.send(value);
                    }
                }
            } catch {
            } finally {
                try {
                    server.close();
                } catch {}
                try {
                    tcpReader.releaseLock();
                } catch {}
                try {
                    socket.close();
                } catch {}
            }
        })();

        return new Response(null, { status: 101, webSocket: client });
    },
};
```

## Worker Pool Metrics

TgWsProxy Android 1.7.4 can reuse prepared Worker WebSocket connections when Worker route is enabled by the effective Wi-Fi/Mobile policy.

- **Worker pool hits**: a prepared Worker connection was reused.
- **Worker pool misses**: no prepared Worker connection was available, so a normal Worker dial was used.
- **Worker pool idle**: prepared Worker connections currently waiting for use.
- **Worker pool errors**: background Worker pool refill errors.

Pool misses are not automatically bad. If misses stay high and errors grow, the Worker route may be unstable or the domain may be incorrect.

## Diagnostics

Use **Test active routes** to check only the routes allowed by the effective policy. Worker route is shown as disabled when the current policy disables it, and as not configured when the Worker domain is empty.

Diagnostics and exported reports do not include raw SSID, SIM/operator values, full domains, or full runtime tokens by default.
