const BOOTSTRAP_SERVICE = "warp-bootstrap";
const BOOTSTRAP_REVISION = "warp-bootstrap-v1";
const BOOTSTRAP_PREFIX = "/warp-bootstrap";
const API_ORIGIN = "https://api.cloudflareclient.com";
const API_VERSION = "v0a4005";
const REGISTER_PATH = `/${API_VERSION}/reg`;
const CLIENT_VERSION = "a-6.11-2223";
const CLIENT_USER_AGENT = "okhttp/3.12.1";
const MAX_REQUEST_BYTES = 8 * 1024;
const MAX_RESPONSE_BYTES = 256 * 1024;
const UPSTREAM_TIMEOUT_MS = 15_000;
const PUBLIC_KEY_PATTERN = /^[A-Za-z0-9+/]{43}=$/;
const REGISTRATION_ID_PATTERN = /^[A-Za-z0-9-]{1,128}$/;
const MAX_BEARER_TOKEN_LENGTH = 2048;

function bootstrapHeaders(extra = {}) {
  return {
    "Cache-Control": "no-store",
    "X-Tgws-Warp-Bootstrap-Revision": BOOTSTRAP_REVISION,
    ...extra,
  };
}

function textResponse(status, text, extraHeaders = {}) {
  return new Response(text, {
    status,
    headers: bootstrapHeaders({
      "Content-Type": "text/plain; charset=utf-8",
      ...extraHeaders,
    }),
  });
}

function jsonResponse(status, value) {
  return new Response(JSON.stringify(value), {
    status,
    headers: bootstrapHeaders({ "Content-Type": "application/json; charset=utf-8" }),
  });
}

export function isWarpBootstrapPath(pathname) {
  return pathname === BOOTSTRAP_PREFIX || pathname.startsWith(`${BOOTSTRAP_PREFIX}/`);
}

function parseContentLength(request) {
  const raw = request.headers.get("Content-Length");
  if (!raw) return null;
  const value = Number.parseInt(raw, 10);
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

async function readJsonObject(request) {
  const contentType = request.headers.get("Content-Type") || "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    return { error: textResponse(415, "application/json required") };
  }

  const declaredLength = parseContentLength(request);
  if (declaredLength !== null && declaredLength > MAX_REQUEST_BYTES) {
    return { error: textResponse(413, "request body too large") };
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength > MAX_REQUEST_BYTES) {
    return { error: textResponse(413, "request body too large") };
  }

  try {
    const value = JSON.parse(new TextDecoder().decode(bytes));
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      return { error: textResponse(400, "JSON object required") };
    }
    return { value };
  } catch {
    return { error: textResponse(400, "invalid JSON") };
  }
}

function upstreamHeaders(authorization = null) {
  const headers = new Headers({
    "Accept": "application/json",
    "Content-Type": "application/json",
    "CF-Client-Version": CLIENT_VERSION,
    "User-Agent": CLIENT_USER_AGENT,
  });
  if (authorization) headers.set("Authorization", authorization);
  return headers;
}

function isValidBearerAuthorization(value) {
  if (typeof value !== "string" || !value.startsWith("Bearer ")) return false;
  const token = value.slice("Bearer ".length);
  if (token.length < 1 || token.length > MAX_BEARER_TOKEN_LENGTH) return false;

  for (let index = 0; index < token.length; index++) {
    const code = token.charCodeAt(index);
    if (code < 0x21 || code > 0x7e) return false;
  }
  return true;
}

async function readBoundedUpstreamResponse(response) {
  const declaredLength = Number.parseInt(response.headers.get("Content-Length") || "", 10);
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    return { error: textResponse(502, "upstream response too large") };
  }

  const body = await response.arrayBuffer();
  if (body.byteLength > MAX_RESPONSE_BYTES) {
    return { error: textResponse(502, "upstream response too large") };
  }
  return { body };
}

function copySafeResponseHeaders(source) {
  const headers = bootstrapHeaders();
  const contentType = source.headers.get("Content-Type");
  const retryAfter = source.headers.get("Retry-After");
  if (contentType) headers["Content-Type"] = contentType;
  if (retryAfter) headers["Retry-After"] = retryAfter;
  return headers;
}

async function fetchWarpApi(path, method, body, authorization, fetchHTTP) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const upstream = await fetchHTTP(`${API_ORIGIN}${path}`, {
      method,
      headers: upstreamHeaders(authorization),
      body: JSON.stringify(body),
      redirect: "manual",
      signal: controller.signal,
    });
    const bounded = await readBoundedUpstreamResponse(upstream);
    if (bounded.error) return bounded.error;
    return new Response(bounded.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: copySafeResponseHeaders(upstream),
    });
  } catch (error) {
    console.log("warp bootstrap upstream failed", {
      revision: BOOTSTRAP_REVISION,
      method,
      error: error?.name || "Error",
    });
    return textResponse(502, "WARP API unavailable");
  } finally {
    clearTimeout(timeout);
  }
}

export async function handleWarpBootstrapRequest(
  request,
  url = new URL(request.url),
  fetchHTTP = fetch,
) {
  if (url.search) return textResponse(400, "query parameters are not supported");

  if (url.pathname === `${BOOTSTRAP_PREFIX}/health`) {
    if (request.method !== "GET") {
      return textResponse(405, "method not allowed", { "Allow": "GET" });
    }
    return jsonResponse(200, { service: BOOTSTRAP_SERVICE, revision: BOOTSTRAP_REVISION });
  }

  if (url.pathname === `${BOOTSTRAP_PREFIX}${REGISTER_PATH}`) {
    if (request.method !== "POST") {
      return textResponse(405, "method not allowed", { "Allow": "POST" });
    }
    const parsed = await readJsonObject(request);
    if (parsed.error) return parsed.error;
    const key = parsed.value.key;
    if (typeof key !== "string" || !PUBLIC_KEY_PATTERN.test(key)) {
      return textResponse(400, "invalid public key");
    }
    return fetchWarpApi(REGISTER_PATH, "POST", { key }, null, fetchHTTP);
  }

  const activationMatch = url.pathname.match(
    new RegExp(`^${BOOTSTRAP_PREFIX}/${API_VERSION}/reg/([A-Za-z0-9-]{1,128})$`),
  );
  if (activationMatch) {
    if (request.method !== "PATCH") {
      return textResponse(405, "method not allowed", { "Allow": "PATCH" });
    }
    const registrationId = activationMatch[1];
    if (!REGISTRATION_ID_PATTERN.test(registrationId)) {
      return textResponse(400, "invalid registration id");
    }
    const authorization = request.headers.get("Authorization") || "";
    if (!isValidBearerAuthorization(authorization)) {
      return textResponse(401, "valid bearer token required", {
        "X-Tgws-Warp-Bootstrap-Error": "invalid_bearer",
      });
    }
    const parsed = await readJsonObject(request);
    if (parsed.error) return parsed.error;
    if (parsed.value.warp_enabled !== true) {
      return textResponse(400, "warp_enabled=true required");
    }
    return fetchWarpApi(
      `${REGISTER_PATH}/${registrationId}`,
      "PATCH",
      { warp_enabled: true },
      authorization,
      fetchHTTP,
    );
  }

  return textResponse(404, "not found");
}

export default {
  async fetch(request) {
    return handleWarpBootstrapRequest(request);
  },
};
