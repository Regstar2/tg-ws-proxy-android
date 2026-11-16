# Release workflow — v1.11.9

**Состояние: PREPARATION, тег не создан.** Последний подтверждённый опубликованный релиз — [v1.11.8](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.8).

## Метаданные

- Версия `1.11.9`, `versionCode 65`, тег `v1.11.9`.
- Пакет `io.github.regstar2.tgwsproxy`, Android 8.0+, `arm64-v8a`.
- Ожидаемые assets: `TgWsProxy-Android-v1.11.9-arm64-v8a.apk` и `TgWsProxy-Android-v1.11.9-arm64-v8a.apk.sha256`.
- [RU notes](v1.11.9.md) · [EN notes](v1.11.9_EN.md) · [GitHub notes](RELEASE_NOTES_v1.11.9.md) · [GO/NO-GO](RELEASE_CHECKLIST.md).

## До создания тега

1. Проверить CI подготовки релиза на финальном PR HEAD и на слитом commit основного `main`.
2. Выполнить `scripts/audit-release.ps1 -ExpectedVersion 1.11.9 -ExpectedVersionCode 65` и `scripts/release.ps1 -Version v1.11.9 -PreflightOnly`. Их успешное выполнение подтверждает только метаданные, не работоспособность приложения.
3. Собрать подписанный ARM64 release candidate с исходным сертификатом и проверить установку поверх v1.11.8 без удаления пользовательских данных, CF-only MTProto, сообщения/медиа и отсутствие деградации других маршрутов.
4. Решить, допускается ли stable-публикация с pre-existing отключённой проверкой TLS сертификатов [#158](https://github.com/Regstar2/tg-ws-proxy-android/issues/158). Отдельно оценить [#155](https://github.com/Regstar2/tg-ws-proxy-android/issues/155) про переподключение из уведомления.
5. После решения GO создать новый **аннотированный tag** на exact verified main SHA, предварительно убедившись, что удалённый тег отсутствует. Не изменять опубликованные теги и не использовать force push.

## После отправки тега

[.github/workflows/release.yml](../../.github/workflows/release.yml) реагирует на `v*`: повторно запускает source CI на GitHub-hosted Windows, затем trusted Windows runner собирает и подписывает APK, генерирует SHA-256 и публикует GitHub Release. Отсутствие секретов или runner должно приводить к отказу, а не к выдаче неподписанного APK.

Затем нужно скачать оба опубликованных assets, сверить SHA-256 и сертификат, проверить `versionCode 65` и установить именно опубликованный APK. До этой проверки статус заметок остаётся «подготовка».

## Проверочные команды

~~~powershell
.\scripts\audit-release.ps1 -ExpectedVersion 1.11.9 -ExpectedVersionCode 65
.\scripts\release.ps1 -Version v1.11.9 -PreflightOnly
~~~
