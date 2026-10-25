# Release checklist — v1.11.4 stable patch

Статус: **подготовка, не опубликовано**. Не отмечать gate PASS по результатам другого commit или Debug APK вместо подписанного финального Release.

## Метаданные, исходники и текст

- [ ] Release-prep PR влит в `main`; известен итоговый release commit SHA.
- [ ] `releaseVersionName = "1.11.4"`, `releaseVersionCode = 60`, README RU/EN и CHANGELOG согласованы.
- [ ] `scripts/audit-release.ps1 -ExpectedVersion 1.11.4 -ExpectedVersionCode 60` — PASS на release source.
- [ ] `scripts/release.ps1 -Version v1.11.4 -PreflightOnly` — PASS на release source.
- [x] Подготовлены RU/EN полные release notes и краткий GitHub Release body.
- [ ] Проверено отсутствие secrets, приватных логов и keystore в tracked files.
- [ ] Перепроверены ссылки на tag, известные ограничения и факты device acceptance.

## Автоматические проверки

- [x] CI PR #116, #117, #119 по отдельным изменениям — PASS.
- [x] Android JVM-тесты / Debug build / Worker regression для PR #119 — PASS (GitHub Actions #40180676121).
- [ ] `scripts/ci.ps1` и GitHub Actions CI на **итоговом release-prep merge commit** — PASS.
- [ ] Подтверждено, что `release.yml` собирает именно tagged source и публикация не дублирует существующий release.
- [ ] `versionName/versionCode` финального подписанного APK проверены посредством `aapt`; подпись — посредством `apksigner`.

## Android device smoke

- [x] Xiaomi / Android 14: конкурентное VPN speed notification, статистика уведомлений on/off, Telegram сообщения/медиа, reconnect, stop/start и notification actions — PASS по acceptance PR #117.
- [x] Подписанный Debug APK PR #119 установлен без удаления приложения, исправление onboarding подтверждено пользователем — PASS.
- [ ] **Release blocker #114:** на Xiaomi/HyperOS long-press плитки открывает главную страницу TgWsProxy вместо «О приложении» при остановленном/запущенном прокси, закрытой/открытой Activity, не переключает состояние; короткий тап и launcher остаются рабочими — **NOT RUN** (см. `docs/testing/quick-settings-long-press.md`).
- [ ] Подключение Telegram, сообщения/медиа, запуск/остановка/переподключение на интегрированной кандидатной версии `v1.11.4`.
- [ ] Установка **финального подписанного Release APK** поверх предыдущей официальной версии без потери настроек — проверяется после формирования publishable artifact.
- [ ] Проверены RU/EN экранов и текста плитки в кандидатной сборке, без clipping.
- [ ] Проверена работа после смены сети/перезапуска приложения (соответствующие поддерживаемые сценарии).

## Артефакты, публикация и post-release

- [ ] Тег `v1.11.4` ещё не существует и ставится только на **итоговый проверенный `main` commit** после device gate.
- [ ] Release workflow для точного tag — PASS, включая signed APK и верификацию manifest/signature.
- [ ] GitHub Release опубликован только один раз; assets — APK и `.sha256`; SHA-256 совпадает с фактическим APK.
- [ ] `versionName 1.11.4`, `versionCode 60`, `com.amurcanov.tgwsproxy` подтверждены у итогового APK.
- [ ] RU/EN release links из опубликованного tag доступны и корректны.
- [ ] **Только после публикации и проверки:** закрыть исходные баг-репорты #112 и #113; не комментировать и не закрывать ранее.
- [ ] Релиз доступен для загрузки, установка/первый запуск проверены.

**Ограничения:** Flowseal Legacy WebSocket остаётся экспериментальным. Debug/smoke APK не является подписанным финальным Release APK. `git push` release tag **немедленно запускает автоматическую публикацию**.
