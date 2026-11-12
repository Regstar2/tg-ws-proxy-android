# Release checklist — v1.11.7

**Состояние:** GitHub Release `v1.11.7` опубликован как stable 2026-11-05, но финальная приёмка на устройстве не завершена. Опубликованные assets: `TgWsProxy-Android-v1.11.7-arm64-v8a.apk` и `.apk.sha256`. Исходный tag указывает на `c9e78cbd2736fc6066e5fb34f04f8638372fb953`. Исходная публичная версия — [v1.11.6](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.6). Отметка PASS относится только к явно указанному commit/артефакту.

## Исходный код и документация

- [x] Реализация нового списка пяти маршрутов и строгого fallback слита в `main` через [PR #137](https://github.com/Regstar2/tg-ws-proxy-android/pull/137); [Issue #135](https://github.com/Regstar2/tg-ws-proxy-android/issues/135) закрыт как completed.
- [x] Изменения сохранения/миграции профилей и MTProto route truth реализованы в ветке PR.
- [x] Код учёта байтов и времени исходящего подключения подготовлен, метрики не обозначены как ping.
- [x] Исходники, CI-аудит и подготовленные release notes согласованы на `1.11.7 / 63`.
- [x] Подготовлены `CHANGELOG.md`, README RU/EN, полные release notes RU/EN, краткие GitHub Release notes, процесс публикации, этот чек-лист.
- [x] Итоговая ветка PR #137 прошла review; PR слит в `main`, Issue #135 закрыт как completed.
- [ ] Проверены все ссылки и RU/EN ресурсы, переводы и отсутствие неактуальных скриншотов текущего UI.

## CI и безопасность

- [x] Windows CI и Worker regression **SUCCESS** на кодовом commit `9d59296f1081c67a866410d9bf86dbf682ea738f`: [Actions #42043647479](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/42043647479). Это **до** версии и релизной документации.
- [x] Release source CI на точном tag `v1.11.7` — **SUCCESS** в [Actions #42063373850](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/42063373850): `scripts/ci.ps1`, Go tests, Android unit/lint/debug build и release metadata audit прошли.
- [x] `scripts/audit-release.ps1 -ExpectedVersion 1.11.7 -ExpectedVersionCode 63` и `scripts/release.ps1 -Version v1.11.7 -PreflightOnly` прошли внутри release-source CI.
- [x] Владелец запустил release workflow; signing keystore восстановлен, release build и publish steps завершились SUCCESS.
- [ ] Общий job `Build and publish release` должен завершиться SUCCESS. В run #42063373850 job помечен failure после публикации; `Post Set up Go` остался in_progress. Job logs недоступны через API (`logs_url=null`), поэтому зависание post-cache cleanup — обоснованная гипотеза, но не подтверждённая точная причина. Исправление workflow проверяется в PR.
- [ ] Нет приватных ключей, WARP-конфигураций, токенов, пользовательских логов или `AGENTS.md` в Git/артефактах.

## Android и маршруты (обязательная приёмка)

- [x] Xiaomi: ранее на `e7dbf969` из лога подтверждены **7** соединений через AWG после недоступного TCP, **4** закрытых двусторонних MTProto-сессии без ошибок передачи. Это **не** тест финальной версии с новыми метриками.
- [x] Пользователь сообщил, что в уведомлении отображается AWG при TCP fallback и оно визуально не «прыгает» вместе с уведомлением VPN. Проверено на более ранней сборке.
- [x] Подписанный DEBUG APK с PR HEAD `d237f87e` установлен на Xiaomi поверх существующего пакета без uninstall/data clear; production и test APK установлены успешно.
- [x] Автоматические UI/instrumentation tests на устройстве: **14/14 PASS**; logcat без FATAL EXCEPTION и ANR.
- [x] Проверены drag grip и стрелки, enabled/disabled routes, Wi-Fi/mobile policies, защита последнего активного маршрута, reset и TCP → AWG actual-backend status.
- [ ] Проверить те же сценарии на опубликованном подписанном **Release APK**.
- [ ] Проверена миграция с установленной **v1.11.6**: пользовательские разрешённые пути и правило `fallback=false` сохранились. Сброс одного профиля не стирает второй.
- [ ] Подтверждены strict-only CF Proxy, отсутствие выключенного fallback; отказ всех включённых маршрутов виден как ошибка без скрытого обхода.
- [ ] Подтверждена фактическая двусторонняя передача через выбранные поддерживаемые пути (CF Proxy, TCP, AWG; Worker/Direct — если настроены и доступны); Telegram отправляет/получает текст и медиа.
- [ ] **На новом APK** во время TCP → AWG подтверждён реальный `actual_backend=awg_warp`, а уведомление показывает AWG; после ошибочных handshake и закрытия старой сессии не становится «Неизвестно».
- [ ] **На новом APK** при загрузке и отправке файла `↓` соответствует входящим данным, `↑` — исходящим, значения не постоянно нулевые; время подключения указано и не называется ping/RTT.
- [ ] Проверены уведомления вместе с VPN, Quick Settings, переключение сети и SOCKS5 на отсутствие регрессий.
- [ ] По [#138](https://github.com/Regstar2/tg-ws-proxy-android/issues/138) установлены причина и влияние массовых Fake TLS отказов **или** документировано обоснованное решение о релизе без исправления после измерения ресурсов/работоспособности; нельзя автоматически считать этот дефект безопасным.

## Release APK и публикация

- [x] Аннотированный tag `v1.11.7` указывает на проверенный commit `c9e78cbd2736fc6066e5fb34f04f8638372fb953`.
- [x] `scripts/release.ps1 -Version v1.11.7` завершился успешно: release APK собран; script проверил `applicationId`, `versionName`, `versionCode` и подпись через `apksigner`, затем создал SHA-256 файл.
- [ ] Скачать опубликованные APK и `.sha256`, независимо пересчитать SHA-256 и подтвердить совпадение с обоими опубликованными файлами.
- [ ] Проверена установка **того же Release APK**, который будет опубликован, поверх v1.11.6 без удаления данных; проверены запуск прокси и двусторонний MTProto.
- [x] Создан один аннотированный tag `v1.11.7` на commit `c9e78cbd` без force.
- [x] [GitHub Release v1.11.7](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.7) опубликован; GitHub API подтверждает ровно два assets: APK (19 717 766 bytes) и `.sha256` (107 bytes), оба `uploaded`.
- [x] Release notes содержат ссылки на RU/EN версии и tag.
- [ ] Общий release workflow должен завершиться SUCCESS после повторного запуска; текущий run опубликовал release, но завершился job-level failure при post-job cleanup.
- [ ] Проверить скачанные артефакты, опубликованный SHA-256, возможность обновления и двусторонний MTProto на том же Release APK.
- [ ] [Milestone v1.11.7](https://github.com/Regstar2/tg-ws-proxy-android/milestone/11) закрыт **только после** фактического завершения обязательных критериев.

**Фактический статус:** **PUBLISHED / VALIDATION INCOMPLETE**. GitHub Release уже опубликован как stable, поэтому прежняя отметка `NO-GO / ещё не опубликован` больше не соответствует действительности. Это не означает, что стабильность подтверждена: обязательная проверка опубликованного Release APK на Xiaomi, обновления с v1.11.6 и поведения уведомлений не выполнена. По [#138](https://github.com/Regstar2/tg-ws-proxy-android/issues/138) нужно принять обоснованное решение о влиянии массовых Fake TLS отказов. Не закрывать milestone до завершения этих проверок.
