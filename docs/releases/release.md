# Release workflow — v1.11.10

**PREPARATION: tag не создан.** Последний опубликованный релиз — [v1.11.9](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.9).

## Метаданные

- versionName 1.11.10, versionCode 66, tag v1.11.10, package io.github.regstar2.tgwsproxy.
- Android 8.0+, minSdk 26 / targetSdk 35, ABI armeabi-v7a, arm64-v8a, Universal.
- Ожидаются три APK и три .sha256: всего шесть файлов. [RU notes](v1.11.10.md) · [EN](v1.11.10_EN.md) · [GitHub text](RELEASE_NOTES_v1.11.10.md) · [GO/NO-GO](RELEASE_CHECKLIST.md).

## До создания тега

1. Пройти Windows CI, Go race, Kotlin unit/lint, ABI audit и ревью diff на финальном commit.
2. Выполнить release metadata preflight (см. команды ниже).
3. С исходным signing certificate собрать и проверить три подписанных APK, SHA-256, package, version и ELF/JNA.
4. Проверить Android CA roots, WSS/443 для CF DC2/DC4, Direct WS и оба Worker-транспорта; проверить отказ на некорректном сертификате controlled fixture.
5. Проверить новый default WS/80 и сохраняющийся false/WSS/443 после обновления и перезапуска, а также переключение только при остановленном прокси. Документировать отсутствие TLS-аутентификации WS/80.
6. Проверить обновление поверх v1.11.9 без удаления данных, MTProto notification reconnect, копирование Telegram-ссылок и сообщения/медиа на Wi-Fi/mobile.
7. После GO создать один аннотированный тег v1.11.10 на проверенном main SHA. Не перезаписывать существующие теги и не использовать force push.

## После создания тега

`.github/workflows/release.yml` запускает exact-tag source CI на GitHub-hosted Windows, затем trusted release build на Windows runner. Скачайте опубликованные три APK и три checksum, проверьте SHA-256, сертификаты и версии. До этого релиз не объявляется опубликованным.

## Команды

```powershell
.\scripts\audit-release.ps1 -ExpectedVersion 1.11.10 -ExpectedVersionCode 66
.\scripts\release.ps1 -Version v1.11.10 -PreflightOnly
# Только при наличии исходного release-signing.env:
.\scripts\release.ps1 -Version v1.11.10
```
