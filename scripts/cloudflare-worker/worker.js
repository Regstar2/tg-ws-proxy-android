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
		const sid = url.searchParams.get("sid") ?? "?";

		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];
		server.accept();

		let wsToTcpBytes = 0;
		let tcpToWsBytes = 0;
		let relayClosed = false;

		console.log("apiws accepted", { sid, dst, dc, media });

		const socket = connect(
			{ hostname: dst, port: 443 },
			{ secureTransport: "off", allowHalfOpen: true },
		);
		const tcpReader = socket.readable.getReader();
		const tcpWriter = socket.writable.getWriter();
		const enqueueWrite = createWriteQueue(tcpWriter);

		const closeRelay = (reason) => {
			if (relayClosed) {
				return;
			}
			relayClosed = true;
			console.log("relay close", {
				sid,
				dst,
				reason,
				wsToTcpBytes,
				tcpToWsBytes,
			});
			try {
				server.close();
			} catch {}
			try {
				socket.close();
			} catch {}
		};

		socket.opened
			.then(() => console.log("tcp opened", { sid, dst }))
			.catch((error) => {
				console.error("tcp open failed", {
					sid,
					dst,
					error: error?.message ?? String(error),
				});
				closeRelay("tcp_open_failed");
			});

		server.addEventListener("message", async (event) => {
			try {
				const chunk = await toBytes(event.data);
				if (!chunk || chunk.byteLength === 0) {
					return;
				}
				await enqueueWrite(chunk);
				wsToTcpBytes += chunk.byteLength;
			} catch (error) {
				console.error("ws->tcp failed", {
					sid,
					dst,
					error: error?.message ?? String(error),
				});
				closeRelay("ws_to_tcp_failed");
			}
		});

		server.addEventListener("close", async () => {
			try {
				await tcpWriter.close();
			} catch {}
			closeRelay("ws_closed");
		});

		server.addEventListener("error", () => {
			closeRelay("ws_error");
		});

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
					server.send(value);
				}
			} catch (error) {
				console.error("tcp->ws read failed", {
					sid,
					dst,
					error: error?.message ?? String(error),
				});
			} finally {
				try {
					tcpReader.releaseLock();
				} catch {}
				closeRelay("tcp_read_done");
			}
		})();

		const requestedProtocols = (request.headers.get("Sec-WebSocket-Protocol") || "")
			.split(",")
			.map((value) => value.trim().toLowerCase());
		const headers = requestedProtocols.includes("binary")
			? { "Sec-WebSocket-Protocol": "binary" }
			: undefined;

		return new Response(null, { status: 101, webSocket: client, headers });
	},
};
