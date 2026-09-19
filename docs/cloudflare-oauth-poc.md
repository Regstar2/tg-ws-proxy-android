# Cloudflare OAuth + Worker deployment PoC

Актуальность проверки: 2026-09-19. Документ относится к Issue #76 и не заменяет production security review.

## Feasibility

Cloudflare поддерживает OAuth 2.0 Authorization Code для third-party clients. Для browser/mobile/desktop/CLI public client требуется PKCE S256 и token_endpoint_auth_method=none; client secret в Android APK не нужен.

Новые OAuth clients создаются private. Для public visibility Cloudflare требует client name, logo, client URL, scopes и подтверждение владения доменом client URL через DNS. Перевод client в public необратим.

OAuth endpoints: authorization https://dash.cloudflare.com/oauth2/auth, token https://dash.cloudflare.com/oauth2/token, revoke https://dash.cloudflare.com/oauth2/revoke.

Cloudflare указывает, что OAuth scope names соответствуют API token permission names. Документация создания OAuth client использует workers-platform.read и workers-platform.write как Workers scope example. Перед production registration фактические scope IDs нужно сверить через GET /client/v4/oauth/scopes.

Для Worker deployment endpoint PUT /accounts/{account_id}/workers/scripts/{script_name} требует Workers Scripts Write. PoC отправляет multipart modules chunk-relay-status-worker.js, chunk-relay-worker.js и worker.js, binding CHUNK_RELAY -> ChunkRelaySession и declarative export ChunkRelaySession с storage=sqlite.

Declarative exports используется вместо legacy migrations: текущий Cloudflare API автоматически provision-ит новый SQLite Durable Object namespace при первом deploy и сопоставляет его при последующих deploy того же class.

После upload PoC включает workers.dev route, читает account subdomain через GET /accounts/{account_id}/workers/subdomain, строит https://<worker>.<subdomain>.workers.dev и выполняет HTTP smoke-test.

## Multi-account model

Один OAuth consent может дать доступ к нескольким Cloudflare accounts. Повторная команда Add Cloudflare login создаёт независимую OAuth session, поэтому несколько Cloudflare identities могут сосуществовать.

Storage model: OAuth session -> encrypted access token -> list of Cloudflare accounts. Активный account выбирается явно; deploy/update/delete используют token именно его session.

PoC не запрашивает и не хранит refresh token. Access token сохраняется только в app-private SharedPreferences в AES/GCM ciphertext, а ключ создаётся в Android Keystore. После expiry требуется повторный вход.

## OAuth client setup

1. Grant type: authorization_code.
2. Response type: code.
3. Token authentication method: none.
4. PKCE: S256.
5. Redirect URI: tgwsproxy://oauth/cloudflare.
6. Начальные Workers scopes для PoC: workers-platform.read workers-platform.write. После real-device PoC scope set нужно сузить до реально необходимых.
7. Для публичного приложения выполнить publisher domain verification и затем перевести client visibility в public.

Client ID передаётся при сборке, secret отсутствует:

    .\gradlew.bat :app:assembleDebug `
      -PTGWSPROXY_CF_OAUTH_CLIENT_ID="<client-id>" `
      -PTGWSPROXY_CF_OAUTH_SCOPES="workers-platform.read workers-platform.write"

Те же два значения можно передать environment variables TGWSPROXY_CF_OAUTH_CLIENT_ID и TGWSPROXY_CF_OAUTH_SCOPES.

Если client ID не задан, экран доступен для inspection, но OAuth login отключён.

## Worker source

APK не содержит вручную поддерживаемую вторую копию Worker source. Gradle перед сборкой копирует worker.js, chunk-relay-worker.js и chunk-relay-status-worker.js из scripts/cloudflare-worker/ в generated assets. Android deploy поэтому использует текущие production source-файлы репозитория.

## Deploy behavior

- новый worker name: create/deploy;
- существующий worker name: PUT того же script и update;
- delete: DELETE /accounts/{account_id}/workers/scripts/{script_name};
- успешный workers.dev endpoint автоматически добавляется в TgWsProxy Worker Pool, pool включается, endpoint выбирается активным;
- повторный deploy того же URL не создаёт дубликат Worker Pool entry.

## Manual acceptance gate

Без зарегистрированного Cloudflare OAuth client и реального Android device нельзя считать выполненными следующие пункты:

- Cloudflare принимает redirect URI текущего Android PoC;
- consent с Workers scopes реально возвращает ожидаемые account resources;
- выбранные scopes достаточны и могут быть сужены;
- upload создаёт ChunkRelaySession / SQLite namespace и CHUNK_RELAY binding;
- workers.dev smoke проходит;
- второй Cloudflare login сосуществует с первым;
- redeploy сохраняет Durable Object mapping;
- delete удаляет test Worker.

До этой проверки Issue #76 остаётся открытым.
