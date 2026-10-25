# Release workflow — v1.11.4

Актуальный target: **v1.11.4 stable patch**. Последний опубликованный релиз — `v1.11.3`; `v1.11.4` публикуется только после закрытия обязательных gate, без переименования существующих тегов.

## Метаданные

- `versionName 1.11.4`
- `versionCode 60`
- Git tag: `v1.11.4` (точный **проверенный merge commit в `main`**; тег не создан до выполнения gate)
- APK: `TgWsProxy-Android-v1.11.4-arm64-v8a.apk`
- SHA-256: `TgWsProxy-Android-v1.11.4-arm64-v8a.apk.sha256`
- [Русские заметки](v1.11.4.md) · [English](v1.11.4_EN.md)
- [Краткий текст GitHub Release](RELEASE_NOTES_v1.11.4.md)
- [Подробный release checklist](RELEASE_CHECKLIST.md)

## Перед созданием тега

1. Убедитесь, что release-prep PR влит в `main`; `scripts/ci.ps1` и GitHub Actions CI **итогового commit** завершились PASS.
2. Проверьте согласованность `1.11.4 / 60` во всех файлах: `./scripts/audit-release.ps1 -ExpectedVersion 1.11.4 -ExpectedVersionCode 60` и `./scripts/release.ps1 -Version v1.11.4 -PreflightOnly`.
3. **Обязательная недостающая проверка:** на Xiaomi / Android 14 / HyperOS выполнить [long-press device smoke](../testing/quick-settings-long-press.md) — для работающего/остановленного прокси, холодного/повторного открытия Activity, коротких нажатий и launcher. Исходный статус этого теста — **NOT RUN**; перед созданием тега нужен фактический результат.
4. Проверить запуск/остановку, Telegram сообщения/медиа, reconnect и сохранение пользовательских настроек в кандидатной сборке. Проверка уведомлений и первоначальной инструкции отдельно документирована в PR #117 и #119; это не подменяет проверку финального APK.
5. Подтвердить доступность официального release-signing keystore для Actions и отсутствие секретов в Git; ранее опубликованный release подписан тем же ключом.
6. Проверить финальный commit и отсутствие уже существующего `v1.11.4` tag/release. **Публикация запускается автоматически при push tag** — не создавайте тег, если gate 1–5 не закрыты.

## Создание GitHub Release

После выполнения всех gate отправьте **один аннотированный тег** `v1.11.4` на конкретный проверенный `origin/main` commit. Не создавайте GitHub Release вручную через `gh release create`: файл `.github/workflows/release.yml` выполняет эту операцию после `git push origin refs/tags/v1.11.4`.

GitHub-hosted Windows workflow:
1. Checkout exact tag (не случайный HEAD `main`).
2. Запуск `scripts/ci.ps1` для tagged source.
3. Восстановление signing keystore из Actions secrets (временный каталог runner).
4. `scripts/release.ps1 -Version v1.11.4`: сборка Release, сверка package/version/signature, SHA-256 и два файла в `dist/`.
5. Публикация `v1.11.4` с содержимым `RELEASE_NOTES_v1.11.4.md` и ровно двух assets.

Ожидаемый CI/Release статус не считается PASS до фактического завершения соответствующего workflow. Не переносите тег, не используйте `--force` и не создавайте второй релиз для того же tag.

## После публикации

1. Откройте [GitHub Release v1.11.4](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.4) и проверьте завершение workflow, соответствие фактического tag SHA, APK, checksum и текстовых ссылок.
2. Скачайте опубликованный финальный **подписанный Release APK**, проверьте SHA-256 и установите поверх прежней официальной версии **без удаления данных**; проверьте локальные настройки, подключение Telegram и поведение плитки/уведомлений.
3. Только **после публикации и её проверки** закрывайте исходные пользовательские баг-репорты [#112](https://github.com/Regstar2/tg-ws-proxy-android/issues/112) и [#113](https://github.com/Regstar2/tg-ws-proxy-android/issues/113). Не закрывайте их при merge исправлений или выпуске release-prep PR.
4. Финализируйте release checklist, фиксируя фактические результаты и непроведённые проверки. Укажите известные OEM-ограничения, если long-press тест не прошёл.

Секреты `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD` и `RELEASE_KEY_ALIAS` должны храниться только в GitHub Actions secrets.
