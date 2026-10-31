# Release workflow — v1.11.6

**Стадия:** release preparation. Последний опубликованный stable-релиз перед подготовкой — [v1.11.5](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.5). Цель [milestone #10](https://github.com/Regstar2/tg-ws-proxy-android/milestone/10) — исправления CF Proxy и отдельный Android application ID. Документация сама по себе не означает публикацию v1.11.6.

## Метаданные

- `versionName = "1.11.6"`, `versionCode = 62`, tag `v1.11.6`.
- `applicationId = "io.github.regstar2.tgwsproxy"`, Kotlin `namespace = "com.amurcanov.tgwsproxy"`.
- Название в интерфейсе: `TgWsProxy`. Требования: Android 8.0+ / `arm64-v8a`.
- [Полные RU notes](v1.11.6.md) · [EN](v1.11.6_EN.md) · [Текст GitHub Release](RELEASE_NOTES_v1.11.6.md) · [Checklist](RELEASE_CHECKLIST.md).
- Ожидаемые артефакты: `TgWsProxy-Android-v1.11.6-arm64-v8a.apk` и `.apk.sha256`.

## Обязательные проверки до тега

1. Подтвердить интеграцию #125 / #126 и PR #131; решить оставшиеся критерии [#128](https://github.com/Regstar2/tg-ws-proxy-android/issues/128) перед закрытием milestone.
2. Сверить Gradle, README RU/EN, CHANGELOG и три release notes по `1.11.6 / 62` и отдельному `applicationId`. Название приложения и Kotlin-пакеты не переименовывать.
3. Запустить `scripts/ci.ps1`, включая `scripts/audit-release.ps1 -ExpectedVersion 1.11.6 -ExpectedVersionCode 62` и `scripts/release.ps1 -Version v1.11.6 -PreflightOnly`. Windows CI и Worker transport regression должны завершиться SUCCESS на окончательном `main`.
4. Проверить выпуск подписанного **Release APK**: `aapt` для package, versionName/versionCode; `apksigner` для подписи; SHA-256 для итогового файла. Не добавлять сертификаты, ключи, пароли или диагностику соединений в репозиторий.
5. Выполнить приёмку: отдельная установка, MTProto, CF pool exhaustion/cooldown, stop CF → AWG, разрешённый fallback, оба Worker transport, Quick Settings.
6. Проверить, что tag `v1.11.6` ещё отсутствует, и зафиксировать точный проверенный `origin/main` SHA.

## Публикация

Однократно создать **аннотированный tag** `v1.11.6` на проверенном `main` и отправить его без force. Существующий [release workflow](../../.github/workflows/release.yml) запускается на tag:

1. Checkout точного tag и повторное выполнение `scripts/ci.ps1`.
2. Восстановление release-signing secrets во временный каталог доверенного CI.
3. `scripts/release.ps1 -Version v1.11.6`: сборка, проверка package/version/signature, вычисление SHA-256.
4. Публикация кратких notes и двух артефактов; существующий релиз нельзя молча перезаписывать.

Тестовый подписанный Debug APK **не** является Release APK. Не выполнять публикацию при неуспешном gate.

## После публикации

Сверить tag commit, подпись, хэш и Assets на GitHub, установить фактически опубликованный APK, проверить основной MTProto-сценарий, смену сетей и маршрутов. Учитывать, что новый `applicationId` устанавливается отдельно: настройки другого пакета не мигрируют автоматически, а два работающих прокси не могут слушать один порт одновременно. Закрыть milestone только после завершения обязательного [чек-листа](RELEASE_CHECKLIST.md).
