# Release notes v1.10.12

Крупный Android-релиз после длинной локальной ветки. Основной сценарий теперь -
MTProto Proxy на `127.0.0.1:1443` с маршрутом через Cloudflare Proxy.

`versionName` **1.10.12** · `versionCode` **50**

## Главное

- MTProto Proxy стал frontend по умолчанию. SOCKS5/WebSocket оставлен как режим
  совместимости на том же локальном порту.
- `MTProto -> cf_proxy_ws` проверен вручную на мобильной сети и Wi-Fi.
- Добавлен Fake TLS secret: `dd<secret>` без masking domain и
  `ee<secret><domain_hex>` при указанном домене.
- Добавлен optional passthrough для probe-соединений на masking domain.
- Дефолты маршрутов изменены под телефон: на мобильной сети только
  `cf_proxy_ws`, на Wi-Fi сначала `cf_proxy_ws`, затем direct/TCP fallback.

## Из Flowseal

- cooldown для Telegram IP в direct WebSocket;
- возрастная ротация WS pool;
- listener watchdog для локального listener;
- test DC support (`dc >= 10000`, `/apiws_test`, `force_test_dc`);
- quality gate для обновления CF-domain pool.

## Android/UI

- Главный экран стал короче: без кнопки Worker Pool, без раскрытия подробностей
  и без строки Worker Pool в состоянии.
- MTProto больше не помечается как experimental.
- Уведомление и диагностика показывают frontend и фактический route backend.
- Runtime-сбор логов и persistent file logs выключены по умолчанию.
- README и скриншоты обновлены под текущий интерфейс.

## Known issue

Worker Pool пока медленный. Код, UI и диагностика оставлены, но основной маршрут
для обычного использования - Cloudflare Proxy.

## Проверено

- `.\gradlew.bat testDebugUnitTest`
- `.\gradlew.bat assembleDebug`
- `cd native\tgwsproxy && go test ./...`
- APK установлен на телефон; MTProto через CF Proxy проверен на мобильной сети и
  Wi-Fi.
