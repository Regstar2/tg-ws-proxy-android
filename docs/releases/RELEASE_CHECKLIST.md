# Release checklist — v1.11.3 stable patch

## Метаданные и исходники

- [ ] `main` содержит только проверенные изменения версии 1.11.3.
- [ ] `releaseVersionName = "1.11.3"` и `releaseVersionCode = 59`.
- [ ] `scripts/audit-release.ps1 -ExpectedVersion 1.11.3 -ExpectedVersionCode 59` — PASS.
- [ ] RU/EN release notes и CHANGELOG соответствуют исходникам.
- [ ] Проверено отсутствие keystore, токенов и пользовательских логов в Git.

## Проверки

- [ ] `scripts/ci.ps1` на итоговом release commit — PASS.
- [ ] JVM unit tests, native tests и debug APK build — PASS.
- [ ] `scripts/release.ps1 -Version v1.11.3 -PreflightOnly` — PASS.
- [x] Плитка запускала, останавливала и повторно запускала локальный прокси на Android 14 / Xiaomi HyperOS (логи владельца от 2026-10-22).
- [ ] Проверка финального подписанного release APK на том же устройстве.
- [ ] Запуск после изменения сети, после перезагрузки и невалидных настроек.
- [ ] Сообщения/медиа Telegram через рабочий маршрут после обновления.
- [ ] Проверка RU/EN иконки и текста плитки без clipping.

## Артефакты и публикация

- [ ] Tag `v1.11.3` указывает на итоговый проверенный `main`.
- [ ] Release workflow повторно проверил исходники и создал подписанный release APK.
- [ ] APK имеет `versionName 1.11.3`, `versionCode 59`, корректный package и подпись.
- [ ] В GitHub Release приложены ровно APK и `.sha256`; проверены их фактические hash и размер.
- [ ] Страница релиза доступна; загрузка и установка APK проверены.

Flowseal Legacy WebSocket остаётся экспериментальным. Не обозначайте промежуточный Debug/smoke APK как стабильный релизный артефакт.
