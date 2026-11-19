# Issue #158 — проверка TLS-сертификатов native-транспортов (подзадача 1)

## Граница изменений

Исправление касается только upstream TLS в Go: Direct WebSocket, Cloudflare Proxy WebSocket (MTProto/SOCKS5/RouteProbe), Worker WebSocket, Worker HTTP chunk relay и диагностических Worker-профилей (Go TLS/uTLS). Порядок маршрутов и обработка MTProto не изменяются. Чеклист других подзадач #158 остаётся открытым.

## Trust store и SNI

- На Linux и Windows используется стандартное доверенное хранилище Go / ОС (RootCAs=nil).
- На Android 14+ корни CA дополнительно читаются из \`/apex/com.android.conscrypt/cacerts\` и объединяются с \`x509.SystemCertPool()\` (в том числе ради совместимости со старым Android). Хранилище кэшируется в рамках процесса; после обновления системных CA приложение следует перезапустить.
- Если доверенные корни недоступны, TLS не должен переходить в режим trust-all. Подтверждение доступности CA-каталогов на реальном Android 14–16 устройстве остаётся отдельной проверкой.
- Для Cloudflare Proxy TLS ServerName равен базовому домену, HTTP Host — \`kwsN.<base-domain>\`, путь — \`/apiws\` (#156).
- Direct WS и Worker проверяются по своим ожидаемым DNS-именам, независимо от того, был ли TCP dial выполнен по имени или IP.
- Ошибки цепочки сертификата и hostname mismatch нельзя обходить альтернативными TLS-кривыми, другим IP того же недоверенного endpoint или ослаблением проверки.

## Автоматические проверки

Из корня Go-модуля \`native/tgwsproxy\`:

\`\`\`powershell
go test ./...
go test -race ./...
\`\`\`

Тесты включают доверенную цепочку и SNI/Host для DC2/DC4, IP dial fallback, Direct WS и Worker, expired/untrusted/self-signed/hostname mismatch, отклонение соединения до HTTP upgrade, запрет TLS-curve retry при ошибке сертификата, проверку CA APEX-файлов и непрерывность Worker HTTP chunk relay. Existing unit tests также проверяют cancellation, retries и TLS-curve fallback. В рамках CI скрипт \`scripts/ci.ps1\` собирает Android-артефакт.

## Android acceptance — требует фактического устройства

1. Использовать проверенный SHA кандидатной ветки, собрать и установить debug APK по проектному пайплайну (без деинсталляции и очистки данных).
2. Выполнить \`scripts/issue-156-cfproxy-device-smoke.ps1 -SelfTest\` для проверки отчётности, затем на подключённом устройстве \`scripts/issue-156-cfproxy-device-smoke.ps1 -Seconds 90\`. Скрипт временно включает CF-only, собирает данные и восстанавливает настройки. Проверить \`settingsRestored=true\`, выбранный \`cf_proxy_ws\`, duplex DC2/DC4, и отсутствие certificate verification errors при валидных доменах.
3. Отдельно проверить Direct WS и оба Worker-транспорта (legacy WebSocket и chunk relay) с корректными HTTPS-сертификатами. Проверить сообщения и загрузку медиа в Telegram; \`up_bytes>0/down_bytes>0\` — лишь транспортный duplex, не доказательство доставки.
4. Проверить Wi-Fi и мобильную сеть, смену сети, повторное подключение, timeout/cancel и хотя бы один IP fallback. Если часть сценариев недоступна — указать NOT_TESTED, а не PASS.
5. Для неверного сертификата использовать изолированный контролируемый TLS fixture, а не подменять чужие домены в работающем Telegram. Ожидается отказ с ошибкой проверки цепочки/hostname, без trust-all и повторного рукопожатия с ослабленным профилем.

Результаты, версии Android/Go, сеть, commit SHA и обезличенные логи внести в PR. До фактического Android smoke пункт проверки устройства не отмечать выполненным. Не публиковать релиз с непроверенной совместимостью CA на целевых версиях Android.
