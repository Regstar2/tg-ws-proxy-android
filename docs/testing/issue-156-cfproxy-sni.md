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

## Автоматизированный Android CF-only smoke (#156)

В debug-only APK добавлен DUMP-protected ADB receiver CfProxySmokeConfigReceiver. Он сохраняет исходные настройки и временно устанавливает единственный маршрут cf_proxy_ws для Wi-Fi, mobile и unknown. Скрипт останавливает прокси, восстанавливает настройки и удаляет временный сырой logcat. В release APK приёмник отсутствует.

Для сборки оригинальным release-ключом и запуска проверки из отдельного worktree:

~~~powershell
& .\scripts\issue-156-cfproxy-device-smoke.ps1 -InstallSignedDebug -SigningRoot 'C:\Base\projects\TGWSProxyAndroid' -Seconds 90
~~~

Скрипт использует существующий issue-135-rebuild-device.ps1: Android unit tests, lint, native Android build, подписанный APK, безопасное adb install -r без удаления пользовательских данных. Локальный Windows Go-тестовый exe при этом не запускается. Требуются устройство ARM64, оригинальный ключ, выбранный MTProto frontend и Telegram.

Скрипт открывает публичный Telegram-канал без отправки сообщений и анализирует фактические selected_backend, actual_backend, fallback_used, up_bytes/down_bytes и знаки DC. Итог CF_DUPLEX_OBSERVED_MEDIA_UNVERIFIED означает только двусторонние байты через Cloudflare; он не доказывает доставку пользовательских сообщений или загрузку медиа. FAIL_OFF_ROUTE и FAIL_NO_DUPLEX — отрицательные результаты, отсутствие закрытых сессий — INCONCLUSIVE. На недоступных сетях операторов сохраняется NOT_TESTED. Отчёт artifacts/issue156/<timestamp>/report.json содержит только агрегированные показатели.
