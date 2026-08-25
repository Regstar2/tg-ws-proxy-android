# TgWsProxy Android v1.10.13

Релиз сосредоточен на стабильности proxy runtime, синхронизации актуальных методов работы с Flowseal и подготовке Android-приложения к более безопасным последующим обновлениям.

Основной сценарий остаётся прежним:

`Telegram → MTProto Proxy 127.0.0.1:1443 → Cloudflare Proxy → Telegram DC`

`versionName` **1.10.13** · `versionCode` **51** · ABI **arm64-v8a**

## Главное

- Исправлена обработка fragmented WebSocket-сообщений в MTProto runtime.
- Continuation frames теперь корректно собираются в единое сообщение.
- Добавлено ограничение **16 MiB** для отдельных WebSocket frames и собранных fragmented messages.
- При ошибках и отклонённых WS-сообщениях underlying connection корректно закрывается.
- Улучшены Worker failover и session pool.
- Worker failover гарантированно останавливается после первого успешного endpoint.
- Исправлен лишний background refill Worker pool при уже выполняющемся foreground connection.
- При одинаковом времени создания Worker порядок остаётся стабильным вместо случайного UUID-порядка.

## Синхронизация с Flowseal

Проведён отдельный аудит актуального `Flowseal/tg-ws-proxy` вплоть до upstream commit `b2a8074`.

В Android runtime перенесены применимые изменения:

- WebSocket fragmentation / continuation handling;
- bounded WebSocket receive path;
- 16 MiB frame/message guards;
- Worker first-success behavior;
- безопасная часть обновлённой Worker pool semantics.

Android-специфичная логика сохранена там, где прямой перенос Flowseal мог изменить поведение приложения:

- route policies для Wi-Fi и mobile data;
- adaptive routing;
- CF-domain health/cooldown;
- Android Worker destination/media logic;
- watchdog;
- Fake TLS;
- diagnostics.

Flowseal остаётся reference upstream для proxy runtime, а платформенный слой реализуется отдельно для Android.

## Обратная связь

Добавлен отдельный экран:

**Настройки → Обратная связь**

Доступны:

- **Сообщить об ошибке**
- **Предложить функцию**

Используются официальные GitHub Issue Forms.

Приложение не содержит встроенного GitHub PAT и автоматически не отправляет:

- runtime logs;
- proxy credentials;
- Telegram data;
- IP-адреса;
- secrets.

Безопасный app/device context копируется только по явному действию пользователя.

## Обновления

Добавлен экран:

**Настройки → Обновления**

Приложение умеет:

- асинхронно проверять официальный GitHub Releases feed;
- сравнивать версии по SemVer;
- корректно учитывать stable/prerelease;
- показывать доступную версию и release notes;
- открывать только официальную страницу релиза `Regstar2/tg-ws-proxy-android`.

Release notes отображаются в компактном читаемом виде с возможностью раскрыть полное описание.

Проверка обновлений:

- не скачивает APK автоматически;
- не устанавливает APK автоматически;
- не требует дополнительных install permissions;
- не влияет на запуск и работу proxy runtime.

## CI и выпуск

Инфраструктура проекта переработана:

- публичные Pull Requests проверяются на GitHub-hosted Windows;
- Project Sync выполняется на GitHub-hosted runner;
- persistent self-hosted runner оставлен только для owner-controlled release/signing;
- добавлена проверка release metadata;
- добавлена проверка RU/EN localization parity;
- проверяется отсутствие private/local файлов и signing material в Git;
- добавлен поиск типовых credential/private-key signatures;
- release APK проверяется перед публикацией;
- для release artifact формируется SHA-256.

Release signing material и keystore не хранятся в репозитории.

## Текущие маршруты

Поддерживаются:

- `cf_proxy_ws` — основной рекомендуемый маршрут;
- `direct_ws`;
- `cf_worker_ws`;
- `tcp_fallback`.

MTProto Proxy остаётся основным local frontend.

SOCKS5/WebSocket сохранён как compatibility mode.

## Known issues

**Worker Pool** всё ещё заметно медленнее основного Cloudflare Proxy path и остаётся дополнительным/диагностическим режимом.

Доступность `direct_ws`, `cf_proxy_ws` и `tcp_fallback` зависит от конкретной сети и ограничений провайдера.

Приложение не является системным VPN и проксирует только трафик, который Telegram направляет на локальный proxy frontend.

## Проверено

- native Go tests;
- Android unit tests;
- debug APK build;
- release/compliance audit;
- RU/EN resource parity;
- Windows PowerShell 5.1 release audit;
- GitHub-hosted Windows CI;
- установка Android debug build;
- MTProto proxy;
- Telegram messages/media;
- reconnect;
- Feedback / Updates screens.

---

Следующий этап разработки — дальнейшее сближение Android proxy paths с актуальной реализацией Flowseal при сохранении Android-specific lifecycle и network policy.
