# Исторический чек-лист подготовки v1.11.9

Этот документ сохранён как снимок подготовки. Статусы PREPARATION ниже относятся к периоду до фактической публикации v1.11.9 (16 ноября 2026), а не к текущему состоянию релиза.

# Release checklist — v1.11.9

**Состояние: PREPARATION / NO-GO до проверки финального кандидата.** PR #157 объединён с main. Владелец 16.11.2026 подтвердил работоспособность Cloudflare Proxy на Android с тестовым исправлением; это не доказательство успешной установки и работы финального подписанного APK 1.11.9. Исторический чек-лист v1.11.8: [RELEASE_CHECKLIST_v1.11.8.md](RELEASE_CHECKLIST_v1.11.8.md).

## Scope и документация

- [x] Issue [#156](https://github.com/Regstar2/tg-ws-proxy-android/issues/156) закрыт, [PR #157](https://github.com/Regstar2/tg-ws-proxy-android/pull/157) слит; итоговый SHA `96b4c8a`.
- [x] Источник адаптации: [malordin, commit a98fd27](https://github.com/malordin/tg-ws-proxy-android/commit/a98fd27c384452b8b0e78a4273abf005077aad6b).
- [x] Автоматические проверки исправления: [PR CI #43934207261](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43934207261) и [интеграционный main CI #43935254597](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43935254597) — PASS.
- [ ] В финальном release commit версия `1.11.9 / versionCode 65` совпадает с README RU/EN, CHANGELOG и заметками релиза.
- [ ] Проверено отсутствие приватных данных, ключей, логов и несвязанных изменений в PR.

## Проверки и подпись

- [ ] Windows CI и Go race/Worker regression — PASS на **финальном commit, который получит тег**.
- [ ] `scripts/audit-release.ps1 -ExpectedVersion 1.11.9 -ExpectedVersionCode 65` — PASS.
- [ ] `scripts/release.ps1 -Version v1.11.9 -PreflightOnly` — PASS.
- [ ] Signed ARM64 release APK: верные package/versionCode/minSdk, сертификат предыдущей версии, подпись и SHA-256.
- [ ] Exact-tag release-source CI и trusted runner release build — PASS.
- [ ] Опубликованы ровно два asset: APK и его `.apk.sha256`; SHA-256 и подпись сверены **после** публикации.

## Проверка на Android

- [x] Пользователь сообщил, что тестовое исправление CF Proxy работает на Android (16.11.2026; без детального DC/media отчёта).
- [ ] Именно signed v1.11.9 Release APK устанавливается поверх v1.11.8 **без удаления приложения** и сохраняет настройки, Worker и AWG/WARP.
- [ ] Telegram отправляет/получает сообщения и медиа через `cf_proxy_ws` без альтернативного fallback.
- [ ] Start/Stop/Reconnect, смена сети и не затронутые маршруты не деградировали на итоговом APK.
- [ ] Для МТС/МегаФон/T2/Билайн, если не проверялись, оставлен статус NOT_TESTED.

## Известные проблемы / решение о публикации

- [ ] Принято документированное решение по pre-existing [#158](https://github.com/Regstar2/tg-ws-proxy-android/issues/158): native WebSocket использует `InsecureSkipVerify=true` и не проверяет сертификат upstream; без решения по риску не заявлять security acceptance.
- [ ] Известный [#155](https://github.com/Regstar2/tg-ws-proxy-android/issues/155) о переподключении из уведомления на Realme/Android 16 оценён как допустимый остаточный дефект или исправлен до stable.
- [x] Сетевая жалоба [#154](https://github.com/Regstar2/tg-ws-proxy-android/issues/154) не закрыта без проверки операторов.

## Тег и публикация

- [ ] После GO создан **один аннотированный tag** `v1.11.9` на проверенном main SHA; существующий тег не перезаписан.
- [ ] Push тега инициировал стандартный `.github/workflows/release.yml`; CI и защита подписи не обходились.
- [ ] В GitHub Release проверены tag, SHA-256, подпись, `versionCode 65` и пригодность APK для обновления v1.11.8.
- [ ] Только после успешной публикации README и релизный индекс переименованы из «подготовка» в «опубликовано».

**Push нового тега автоматически запускает выпуск stable APK. Не отправлять тег до завершения обязательных проверок.**
