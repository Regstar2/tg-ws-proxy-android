# Release workflow — v1.11.5

Цель: **v1.11.5 stable patch** (Quick Settings Tile). Последний опубликованный релиз — **v1.11.4**. Публикация нового релиза ещё не выполнена.

## Метаданные

- `versionName 1.11.5`, `versionCode 61`.
- Tag: `v1.11.5` на итоговом **проверенном merge commit в main**.
- APK: `TgWsProxy-Android-v1.11.5-arm64-v8a.apk`.
- Checksum: `TgWsProxy-Android-v1.11.5-arm64-v8a.apk.sha256`.
- [Полное описание на русском](v1.11.5.md) · [English](v1.11.5_EN.md).
- [Краткий GitHub Release body](RELEASE_NOTES_v1.11.5.md) · [Обязательный чек-лист](RELEASE_CHECKLIST.md).

## Обязательные проверки до тега

1. Release-prep PR влит в `main`. GitHub Actions `Windows CI` и `Worker transport regression` завершились SUCCESS **на итоговом commit**. Не используйте зелёный статус от старого HEAD.
2. `app/build.gradle.kts`, `README.md`, `README_EN.md`, `CHANGELOG.md`, RU/EN notes и текст GitHub Release согласованы по `1.11.5 / 61`.
3. Запущены `./scripts/audit-release.ps1 -ExpectedVersion 1.11.5 -ExpectedVersionCode 61` и `./scripts/release.ps1 -Version v1.11.5 -PreflightOnly`. Тесты CI включают release audit и `testDebugUnitTest / assembleDebug`.
4. Проверен доступ к release-signing secrets GitHub Actions: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, необязательный `RELEASE_KEY_ALIAS`. **Не выводить секреты в консоль.**
5. Зафиксировано device acceptance для [#122](https://github.com/Regstar2/tg-ws-proxy-android/issues/122): установка подписанного тестового APK поверх v1.11.4 без очистки данных, реальный запуск плиткой после Wi-Fi ↔ mobile, активное состояние только после RUNNING, приемлемый отклик. **Тестовый Debug APK не является финальным Release APK**.
6. Проверить другие обязательные критерии [чек-листа](RELEASE_CHECKLIST.md); не заявлять ручные SOCKS5/AWG/OEM/новую установку как PASS, если они не выполнялись.
7. Перед push убедиться, что `v1.11.5` не существует ни локально, ни в origin/GitHub Releases, а tag указывает на **точный** `origin/main`.

## Создание и публикация релиза

Отправка **одного аннотированного тега** `v1.11.5` в origin автоматически запускает `.github/workflows/release.yml`. Тег отправляется **только после** перечисленных проверок. Используйте проверенный PowerShell-блок из release handoff, привязанный к `origin/main` commit; не выполняйте `gh release create` вручную.

GitHub-hosted Windows release workflow:
1. Checkout exact tag и повторный запуск `scripts/ci.ps1`.
2. Восстановление прежнего release keystore из GitHub Actions secrets во временном каталоге.
3. `scripts/release.ps1 -Version v1.11.5`: сборка Release APK; `aapt` сверяет package/versionCode/versionName, `apksigner` проверяет подпись; вычисляется SHA-256.
4. Проверка, что в `dist/` есть только подписанный APK и его checksum.
5. Создание GitHub Release с кратким `RELEASE_NOTES_v1.11.5.md` и двумя assets. Существующий релиз под тем же тегом workflow не перезаписывает.

**Нельзя** переносить опубликованный тег, использовать `--force` или вручную загружать неподтверждённый Debug APK как stable asset. Если workflow не прошёл, публикация считается незавершённой — необходимо разбирать конкретный fail и не утверждать, что APK опубликован.

## После публикации

1. Проверить результат Release workflow и [страницу v1.11.5](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.5): tag SHA, название, notes, RU/EN ссылки, два assets и фактическую SHA-256.
2. Сверить подписанный APK с указанными в manifest `1.11.5 / 61` и установить **финальный** файл поверх официальной v1.11.4 с сохранением данных.
3. Проверить запуск/остановку, Telegram, смену сети при выключенном прокси, реакцию плитки и появление уведомления. Если возникнет регрессия, зафиксировать отдельный issue; не перемещать tag.
4. После подтверждения релизной установки закрыть исходный пользовательский report [#121](https://github.com/Regstar2/tg-ws-proxy-android/issues/121); технический [#122](https://github.com/Regstar2/tg-ws-proxy-android/issues/122) уже закрыт по итогам PR #123.
5. Отметить фактические результаты в [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md), сохраняя NOT RUN для непроверенных сценариев.

Документация не содержит secrets и не требует ручной загрузки APK: публикация выполняется существующим GitHub Release workflow по tag.
