# Issue #156 — Cloudflare Proxy TLS SNI / WebSocket Host

## Изменение

Для Cloudflare Proxy клиент подключается к `kwsN.<base-domain>` (либо к разрешённому IP), но передаёт `<base-domain>` в TLS ClientHello (SNI). В HTTP WebSocket upgrade остаются `Host: kwsN.<base-domain>` и путь `/apiws`. Для MTProto и SOCKS5 применяется одинаковая пара SNI/Host. Диагностика использует исходный upstream host для DNS/TCP, базовый домен для TLS и прежний Host для HEAD/upgrade.

Это **гипотеза исправления TLS-таймаутов**, а не подтверждённая причина всех случаев недоступности Telegram. HTTP 101 означает только успешный WebSocket upgrade; он не доказывает двусторонний MTProto или загрузку медиа. Direct WS, Worker и остальные маршруты не меняются. При ответе HTTP 403/421 не выполняется повторная попытка с прежним SNI: такой обход без отдельных воспроизводимых тестов не включён.

## Проверки

| Проверка | Статус |
| --- | --- |
| Нативные Go tests / race | NOT_RUN — проверить в CI |
| JVM unit tests / Android build | NOT_RUN — проверить в CI |
| scripts/ci.ps1 | NOT_RUN — проверить в Windows CI |
| Фактический двусторонний MTProto / медиа | NOT_TESTED |
| МТС (Москва), МегаФон, T2, Билайн | NOT_TESTED |
| Wi-Fi / Android ARM64, DC2/DC4 и смена сети | NOT_TESTED |

## Device / network acceptance

1. Установить тестовый APK на ARM64, не сбрасывая приложение и Telegram.
2. Отключить остальные fallback-маршруты, оставить только `cf_proxy_ws`. Проверить соответствие selected/actual backend.
3. Для DC2 и DC4 зафиксировать DNS/TCP/TLS/WS этапы; проверить реальную отправку/получение сообщения и передачу медиа, `up_bytes > 0` и `down_bytes > 0`.
4. Повторить после переподключения, Wi-Fi ↔ мобильная сеть, Stop/Cancel, смены маршрута и перезапуска. Проверить отсутствие массовых reconnect, зависших TLS и утечки CF reservations.
5. Сравнить с `main` на той же сети, сохранить только обезличенные логи без IP, secret, Worker URL и пользовательских данных.
6. Для каждой сети отмечать PASS только при подтверждённом двустороннем обмене; иначе FAIL или NOT_TESTED. Исходную жалобу #154 не закрывать без такого подтверждения.
