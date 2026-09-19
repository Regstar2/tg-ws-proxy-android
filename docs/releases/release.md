# Release workflow

Актуальный release target: **v1.11.0 stable**.

## Метаданные

- `versionName 1.11.0`
- `versionCode 54`
- tag: `v1.11.0`
- APK: `TgWsProxy-Android-v1.11.0-arm64-v8a.apk`
- full RU notes: [v1.11.0.md](v1.11.0.md)
- full EN notes: [v1.11.0_EN.md](v1.11.0_EN.md)
- GitHub Release body: [RELEASE_NOTES_v1.11.0.md](RELEASE_NOTES_v1.11.0.md)

## Перед тегом

1. Запустить:
   ```powershell
   .\scripts\ci.ps1
   ```
2. Собрать подписанный release:
   ```powershell
   .\scripts\release.ps1 -Version v1.11.0
   ```
3. Установить signed APK поверх `v1.10.14` и отдельно поверх `v1.11.0-beta.1`; uninstall не использовать как способ обхода проверки подписи.
4. Проверить MTProto/CF, `awg_warp`, создание WARP-профиля, сообщения/media, reconnect и Wi-Fi ↔ mobile.
5. Проверить custom provisioning Worker и поведение переключателя встроенных Worker.
6. Просмотреть экспорт diagnostics/logs на наличие секретов.
7. Зафиксировать результат в [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Автоматические проверки

`.\scripts\ci.ps1` проверяет release metadata/localization/private-file audit, release preflight, Go module/tests, Android unit tests, debug APK build и packaged-resource audit.

`.\scripts\release.ps1 -Version v1.11.0` дополнительно:

- требует release signing material вне Git;
- собирает release APK;
- проверяет package, `versionName` и `versionCode`;
- проверяет APK signature через `apksigner`;
- создаёт ровно APK и SHA-256 в `dist/`.

## Публикация

`.github/workflows/release.yml` запускается для существующего tag `v1.11.0`. Workflow:

1. checkout exact tag;
2. повторно запускает `scripts/ci.ps1`;
3. восстанавливает release keystore из GitHub Actions secrets во временный каталог hosted runner;
4. запускает `scripts/release.ps1 -Version v1.11.0`;
5. проверяет наличие одного APK и одного `.sha256`;
6. создаёт GitHub Release только если предыдущие шаги завершились успешно.

Для stable tag workflow не добавляет флаг prerelease.

## Signing secrets

Repository Actions secrets:

```text
RELEASE_KEYSTORE_BASE64
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_PASSWORD
RELEASE_KEY_ALIAS
```

Keystore, пароли и локальные signing env-файлы не должны попадать в Git, diagnostics или release assets.
