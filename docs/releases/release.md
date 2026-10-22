# Release workflow

Актуальный target: **v1.11.3 stable patch**.

## Метаданные

- `versionName 1.11.3`
- `versionCode 59`
- Git tag: `v1.11.3` (тег ставится только на проверенный commit после слияния release-prep PR)
- APK: `TgWsProxy-Android-v1.11.3-arm64-v8a.apk`
- [Описание на русском](v1.11.3.md) · [English](v1.11.3_EN.md)
- [Краткое описание GitHub Release](RELEASE_NOTES_v1.11.3.md)

## Перед тегом

1. Проверьте `scripts/ci.ps1` и пройденный GitHub Actions CI на итоговом commit.
2. `./scripts/release.ps1 -Version v1.11.3 -PreflightOnly` — проверка соответствия метаданных.
3. Проверьте вручную выключение/запуск прокси и доставку сообщений через Telegram. Отдельно проверьте сохранённые настройки, индикацию плитки, отсутствие ложного ACTIVE и обновление подписанного APK без удаления данных, когда финальная сборка доступна.
4. Подтвердите источники подписи и отсутствие секретов в tracked files.
5. Отправьте точный тег `v1.11.3` из итогового `main`. Не переносите существующий тег и не используйте force-push.

## Автоматическая публикация

`.github/workflows/release.yml` запускается при push tag, проверяет, что тег существует, выполняет `scripts/ci.ps1`, подписывает релизный APK ключом из GitHub Actions secrets, сверяет пакет, версию, подпись и вычисляет SHA-256, затем публикует ровно APK и checksum в GitHub Releases.

Публикация не считается состоявшейся, пока workflow не завершился с результатом PASS, а release assets не доступны в GitHub.

Секреты `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS` хранятся только в Actions secrets; не коммитьте keystore и пароли.
