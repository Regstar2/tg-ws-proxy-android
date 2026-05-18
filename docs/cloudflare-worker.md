# Cloudflare Worker

Бесплатная альтернатива [Cloudflare Proxy](https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/CfProxy.md) без покупки своего домена. Совместимо с подходом [Flowseal/tg-ws-proxy v1.7.0](https://github.com/Flowseal/tg-ws-proxy).

TgWsProxy Android подключается к Worker так:

```text
wss://<worker-domain>/apiws?dst=<telegram-dc-ip>&dc=<dc-id>&media=<0-or-1>
```

В настройках указывается **только домен**, без `https://` и без `/apiws`:

```text
example.username.workers.dev
```

Не используйте чужие публичные Worker. Создайте свой.

## Быстрая настройка

1. Откройте [Cloudflare Dashboard](https://dash.cloudflare.com).
2. Перейдите в **Workers & Pages**.
3. Создайте Worker (**Start with Hello World** → **Deploy**).
4. Нажмите **Edit code**.
5. Вставьте код Worker из раздела ниже.
6. Нажмите **Deploy**.
7. Скопируйте домен вида `example.username.workers.dev`.
8. В TgWsProxy: **Настройки** → **Cloudflare Worker** → вставьте домен.
9. Выберите режим **Worker first** или **Worker only** (для мобильных сетей часто удобен **Worker first**).
10. Нажмите **Проверить Worker**.

На сетях с блокировкой `cloudflare.com` / `workers.dev` может понадобиться обход (например zapret) — см. [инструкцию Flowseal](https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/CfWorker.md).

## Код Worker

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

## Режимы в приложении

| Режим | Поведение |
|-------|-----------|
| Auto | Direct WS → Worker (если задан) → CF pool → TCP |
| Direct + fallback routes | Direct → Worker → CF → TCP |
| Worker first | Worker → CF → Direct → TCP |
| CF first | CF → Worker → Direct → TCP |
| Worker only | Только Worker |
| CF only | Только CF proxy |
| Direct only | Только Direct WS |

`Worker only` and `CF only` do not permit direct TCP passthrough for Telegram-like traffic. Android keeps these mobile-oriented modes intentionally even though Flowseal desktop v1.7.0 removed the separate CF-priority surface.

## TODO

- **Fake TLS** (из desktop Flowseal): оценить применимость для локального SOCKS5 на Android; отдельная задача, не смешивать с Worker.
