# Release checklist — v1.11.6

**Стадия:** подготовка stable patch. CI и подпись итогового Release APK должны проверяться по точному release commit. Отметки ниже относятся только к явно указанным проверкам.

## Изменения и документация

- [x] #125 / PR #129: CF pool exhaustion и ограниченные reconnect интегрированы.
- [x] #126 / PR #130: отмена устаревших CF TLS-попыток и route generation интегрированы.
- [x] PR #131: отдельный Android `applicationId` интегрирован в `main`; имя `TgWsProxy` сохранено.
- [x] `versionName 1.11.6`, `versionCode 62` и новый package согласованы в исходниках/релизных скриптах.
- [x] Подготовлены CHANGELOG, README RU/EN, полные заметки RU/EN, краткие Release notes и процесс публикации.
- [x] По [#128](https://github.com/Regstar2/tg-ws-proxy-android/issues/128) принято решение: оставить задачу открытой до публикации v1.11.6 и закрыть после выпуска; установка подписанного Debug APK с новым Android-пакетом подтверждена. Дополнительная проверка совместимости относится к послерелизной приёмке.

## Сборка, CI, безопасность

- [x] Windows CI, Android unit tests, Debug build и Worker transport regression PASS на итоговых PR HEAD ([#131](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/41234412529), [#132](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/41235662329)).
- [x] Те же обязательные проверки PASS на интегрированном `main` `e7daa4a3a67cfe869cb040364efdccbae340d64d` ([CI #41235993762](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/41235993762)). После изменений релизной документации нужен PASS на новом итоговом `main`.
- [x] `scripts/audit-release.ps1 -ExpectedVersion 1.11.6 -ExpectedVersionCode 62` — PASS в проверке проекта на интегрированном `main`.
- [x] `scripts/release.ps1 -Version v1.11.6 -PreflightOnly` — PASS в проверке проекта на интегрированном `main`.
- [ ] Финальный Release APK: `aapt` подтверждает package `io.github.regstar2.tgwsproxy`, versionName `1.11.6`, versionCode `62`.
- [ ] `apksigner` подтверждает релизную подпись; SHA-256 вычислен по финальному APK.
- [ ] Проверены RU/EN resources, отсутствие секретов, приватных конфигураций и чувствительных сетевых сведений.

## Приёмка приложения и маршрутов

- [x] Подписанный Debug APK с новым `applicationId` устанавливается и запускается.
- [ ] Финальный подписанный Release APK устанавливается независимо от старого package и запускается.
- [ ] Подтверждён MTProto: запуск/остановка прокси, двусторонние сообщения и медиа.
- [ ] CF-only exhaustion, восстановление после cooldown, отсутствие неразрешённого fallback.
- [ ] Отмена старого CF TLS после stop, запуск AWG без устаревшего CF в новом поколении.
- [ ] Не регрессировали Worker Chunk Relay и Legacy WebSocket в поддерживаемых тестовых сценариях.
- [ ] Quick Settings, переключение сетей и локализации не регрессировали.

## Публикация

- [x] PR #131 и PR #132 слиты; исходная релизная подготовка интегрирована в `main`.
- [x] На этапе подготовки tag `v1.11.6` отсутствовал. Перед созданием тега повторно проверить точный SHA окончательного `main`.
- [ ] Отправлен один аннотированный tag `v1.11.6` без force.
- [ ] Release workflow PASS, опубликованы ровно APK и `.apk.sha256`.
- [ ] Проверены SHA-256 опубликованного файла, tag commit и оба языка Release notes.
- [ ] Финальный опубликованный APK проверен, milestone закрыт только по фактическим результатам.

Подписанный Debug APK и старый CI не заменяют проверку финального Release APK.
