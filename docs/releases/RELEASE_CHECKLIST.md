# Release checklist — v1.11.5 stable patch

Статус: **подготовка; Git tag и GitHub Release ещё не созданы**. Любая отметка PASS относится только к указанной сборке/коммиту.

## Метаданные, репозиторий и текст

- [ ] Release-prep PR влит в `main`, известен итоговый release commit SHA.
- [ ] `releaseVersionName 1.11.5` / `releaseVersionCode 61`, README RU/EN, CHANGELOG и три release notes согласованы.
- [ ] `./scripts/audit-release.ps1 -ExpectedVersion 1.11.5 -ExpectedVersionCode 61` — PASS на release source.
- [ ] `./scripts/release.ps1 -Version v1.11.5 -PreflightOnly` — PASS.
- [x] Тексты на русском и английском и краткое описание GitHub Release подготовлены на release-prep ветке (требуется финальный обзор).
- [ ] Нет секретов, приватных конфигураций и случайных отслеживаемых файлов; согласованы ссылки/известные ограничения.
- [ ] Выбран точный tag commit, `v1.11.5` ещё не существует ни в Git, ни в GitHub Releases.

## CI и автоматизация

- [x] [PR #123](https://github.com/Regstar2/tg-ws-proxy-android/pull/123), HEAD a7a97052: [CI #40837472081](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/40837472081) Windows CI и Worker transport regression — PASS.
- [ ] Windows CI и Worker transport regression — PASS **на итоговом release-prep merge commit**.
- [ ] `scripts/ci.ps1`: Android unit tests, debug build, release metadata audit и native regression — PASS на итоговом source.
- [ ] `.github/workflows/release.yml` использует точный tag, нет существующего GitHub Release; Actions release-signing secrets доступны.
- [ ] `aapt` и `apksigner` подтверждают versionName, versionCode, package и release-подпись финального APK.

## Реальное Android-устройство

- [x] На устройстве пользователя подписанный тестовый APK установлен поверх v1.11.4 без удаления данных; локальная Debug-сборка PASS.
- [x] Пользователь подтвердил запуск плиткой после переключения Wi-Fi → mobile без `CONFIG_CHANGED`, а также приемлемую скорость реакции после обновления.
- [x] Диагностика тестовой сборки показывает `TILE_START_REQUESTED`, `FG_NOTIFICATION_SUBMITTED` и `TILE_CONFIRMATION_SAVED`. Фактическое отображение SystemUI отдельно от факта отправки уведомления.
- [ ] На **финальном v1.11.5 Release APK** проверены сохранение данных при обновлении с v1.11.4, реальный RUNNING, Telegram сообщения/медиа, остановка и перезапуск через плитку.
- [ ] Свежая установка: сначала требуется успешный запуск из приложения; без него плитка не запускает прокси.
- [ ] Другие версии Android/OEM, отдельные SOCKS5/AWG/WARP device-сценарии — NOT RUN, не считать PASS.
- [ ] Проверены ru/en подписи плитки/уведомлений в финальной сборке.

## Публикация и post-release

- [ ] Аннотированный tag `v1.11.5` отправлен ровно один раз на проверенный `main`.
- [ ] Release workflow успешно завершился на tagged source и создал **два** файла: `TgWsProxy-Android-v1.11.5-arm64-v8a.apk` и `.apk.sha256`.
- [ ] Checksum соответствует опубликованному APK; выпуск не содержит Debug APK или локальные данные.
- [ ] GitHub Release содержит краткий RU body, обе ссылки на полные RU/EN заметки из tag, compare с v1.11.4.
- [ ] Подтверждены запуск, настройка, уведомление и смена сети **на опубликованном APK**.
- [ ] После успешной публикации и проверки закрыт исходный report [#121](https://github.com/Regstar2/tg-ws-proxy-android/issues/121); до этого он остаётся открытым.

Ограничения: Flowseal Legacy WebSocket экспериментален; на OEM SystemUI возможны особенности. Успешный Debug smoke **не подтверждает** опубликованный Release APK. `git push origin refs/tags/v1.11.5` запускает **автоматическую публикацию**.
