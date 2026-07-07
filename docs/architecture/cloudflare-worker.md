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

Deployable source: [scripts/cloudflare-worker/worker.js](../../scripts/cloudflare-worker/worker.js)

The Worker uses **lazy TCP connect**: it accepts the WebSocket upgrade immediately, waits for the first client frame, then opens the Telegram TCP socket and writes that frame. This avoids Telegram closing an idle TCP connection opened before the MTProto init packet arrives.

```javascript
import { connect } from "cloudflare:sockets";

async function toBytes(data) {
    if (data instanceof ArrayBuffer) {
        return new Uint8Array(data);
    }
    if (Array.isArray(data)) {
        return new Uint8Array(data);
    }
    if (data instanceof Uint8Array) {
        return data;
    }
    if (typeof data === "string") {
        return new TextEncoder().encode(data);
    }
    if (data && typeof data.arrayBuffer === "function") {
        const ab = await data.arrayBuffer();
        return new Uint8Array(ab);
    }
    return new Uint8Array();
}

function createWriteQueue(writer) {
    let chain = Promise.resolve();
    return (chunk) => {
        chain = chain
            .then(() => writer.write(chunk))
            .catch((error) => {
                console.error("write queue failed", {
                    error: error?.message ?? String(error),
                });
                throw error;
            });
        return chain;
    };
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

        const dc = url.searchParams.get("dc") ?? "?";
        const media = url.searchParams.get("media") ?? "?";
        console.log("apiws accepted", { dst, dc, media });

        const requestedProtocols = (request.headers.get("Sec-WebSocket-Protocol") || "")
            .split(",")
            .map((value) => value.trim().toLowerCase());
        const useBinaryProtocol = requestedProtocols.includes("binary");

        const pair = new WebSocketPair();
        const client = pair[0];
        const server = pair[1];
        server.accept();

        let socket = null;
        let tcpReader = null;
        let tcpWriter = null;
        let enqueueWrite = null;
        let readyPromise = null;
        let wsToTcpBytes = 0;
        let tcpToWsBytes = 0;
        let tcpToWsLoopStarted = false;
        let relayClosed = false;

        const closeRelay = (reason) => {
            if (relayClosed) {
                return;
            }
            relayClosed = true;
            console.log("relay close", {
                reason,
                dst,
                wsToTcpBytes,
                tcpToWsBytes,
            });
            try {
                server.close();
            } catch {}
            if (socket) {
                try {
                    socket.close();
                } catch {}
            }
        };

        const writeWsToTcp = async (chunk) => {
            await enqueueWrite(chunk);
            wsToTcpBytes += chunk.byteLength;
            console.log("ws->tcp packet", {
                dst,
                bytes: chunk.byteLength,
                total: wsToTcpBytes,
            });
        };

        const startTcpToWsLoop = () => {
            if (tcpToWsLoopStarted) {
                return;
            }
            tcpToWsLoopStarted = true;
            console.log("relay start", { dst });

            (async () => {
                try {
                    while (true) {
                        const { value, done } = await tcpReader.read();
                        if (done) {
                            break;
                        }
                        if (!value || value.byteLength === 0) {
                            continue;
                        }
                        tcpToWsBytes += value.byteLength;
                        console.log("tcp->ws packet", {
                            dst,
                            bytes: value.byteLength,
                            total: tcpToWsBytes,
                        });
                        server.send(value);
                    }
                } catch (error) {
                    console.error("tcp->ws read failed", {
                        dst,
                        error: error?.message ?? String(error),
                    });
                } finally {
                    console.log("tcp closed", { dst });
                    try {
                        tcpReader.releaseLock();
                    } catch {}
                    closeRelay("tcp_read_done");
                }
            })();
        };

        const initTcp = async (firstChunk) => {
            console.log("ws first packet", { dst, bytes: firstChunk.byteLength });
            console.log("tcp connect start", { dst });

            socket = connect(
                { hostname: dst, port: 443 },
                { secureTransport: "off", allowHalfOpen: true },
            );
            tcpReader = socket.readable.getReader();
            tcpWriter = socket.writable.getWriter();
            enqueueWrite = createWriteQueue(tcpWriter);

            socket.opened
                .then(() => console.log("tcp opened", { dst }))
                .catch((error) =>
                    console.error("tcp open failed", {
                        dst,
                        error: error?.message ?? String(error),
                    }),
                );

            try {
                await socket.opened;
                await writeWsToTcp(firstChunk);
                startTcpToWsLoop();
            } catch (error) {
                console.error("lazy tcp init failed", {
                    dst,
                    error: error?.message ?? String(error),
                });
                closeRelay("tcp_init_failed");
                throw error;
            }
        };

        server.addEventListener("message", async (event) => {
            try {
                const chunk = await toBytes(event.data);
                if (!chunk || chunk.byteLength === 0) {
                    return;
                }

                if (!readyPromise) {
                    readyPromise = initTcp(chunk);
                    await readyPromise;
                    return;
                }

                await readyPromise;
                await writeWsToTcp(chunk);
            } catch (error) {
                console.error("ws->tcp failed", {
                    dst,
                    error: error?.message ?? String(error),
                });
                closeRelay("ws_to_tcp_failed");
            }
        });

        server.addEventListener("close", () => {
            if (!readyPromise) {
                closeRelay("ws_closed_before_first_packet");
                return;
            }
            (async () => {
                try {
                    await tcpWriter?.close();
                } catch {}
                try {
                    socket?.close();
                } catch {}
                closeRelay("ws_closed");
            })();
        });

        server.addEventListener("error", () => {
            closeRelay("ws_error");
        });

        const headers = useBinaryProtocol
            ? { "Sec-WebSocket-Protocol": "binary" }
            : undefined;

        return new Response(null, { status: 101, webSocket: client, headers });
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
