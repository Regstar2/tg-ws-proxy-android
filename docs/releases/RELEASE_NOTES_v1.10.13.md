# TgWsProxy Android v1.10.13

`versionName` **1.10.13** · `versionCode` **51** · ABI **arm64-v8a**

## Главное

- Стабилизирован MTProto WebSocket receive path: continuation/fragmented frames теперь собираются корректно, а размер отдельных и накопленных сообщений ограничен 16 MiB.
- Worker failover и session-pool поведение усилены тестами; miss больше не запускает дублирующий background refill при уже выполняющемся foreground dial.
- Синхронизированы релевантные изменения Flowseal с сохранением Android-специфичной маршрутизации, Fake TLS, watchdog и диагностики.
- Добавлен отдельный экран **Обратная связь** с GitHub Issue Forms без встроенного GitHub PAT.
- Добавлен отдельный экран **Обновления**: асинхронная проверка официальных GitHub Releases, SemVer stable/prerelease policy, release notes и переход только на официальный release URL.
- Экран обновлений не скачивает и не устанавливает APK автоматически и не запрашивает дополнительные install/background permissions.
- Публичная CI переведена на GitHub-hosted Windows; persistent self-hosted runner оставлен только для owner-controlled release/signing path.
- Добавлены Project Sync, release automation, release signing verification и SHA-256 artifact generation.
- Публичная структура и документация приведены к актуальным правилам проекта; private governance/AI/tool state исключены из Git.

## Update delivery

В **Настройки → Обновления** отображается установленная версия и выполняется безопасная проверка `Regstar2/tg-ws-proxy-android` Releases.

При наличии более новой подходящей версии приложение показывает краткое описание релиза и предлагает открыть официальную страницу GitHub Release. Сетевые, timeout, API и malformed-metadata ошибки остаются локальным состоянием экрана и не должны останавливать или перенастраивать proxy runtime.

## Feedback

В **Настройки → Обратная связь** доступны отдельные действия **Сообщить об ошибке** и **Предложить функцию**. Приложение автоматически не прикладывает runtime logs, proxy credentials, Telegram data, IP-адреса или secrets. Копирование app/device context выполняется только по явному действию пользователя.

## Runtime

Основной сценарий остаётся **MTProto Proxy → Cloudflare Proxy** через локальный endpoint `127.0.0.1:1443`.

Worker Pool остаётся необязательным и более медленным development/diagnostics path. `direct_ws` и `tcp_fallback` сохраняются как разрешаемые политикой альтернативы.

## Безопасность релиза

- Release signing material не хранится в Git.
- `scripts/release.ps1 -Version v1.10.13` требует локально настроенные signing variables/keystore, проверяет подпись APK и формирует APK + SHA-256 в `dist/`.
- GitHub Release должен публиковаться только после финального manual acceptance Issue #7.

## Перед публикацией

Обязательная ручная проверка финальной сборки:

- MTProto proxy start/stop/reconnect;
- Telegram messages и media;
- Wi-Fi и mobile data;
- Wi-Fi ↔ mobile reconfigure;
- Feedback и Updates;
- RU/EN;
- diagnostic export на отсутствие чувствительных данных;
- signed release artifact и SHA-256.
