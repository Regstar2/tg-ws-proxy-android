# Release workflow — v1.11.8

**Состояние: подготовка / NO-GO.** Последний опубликованный релиз — [v1.11.7](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.7). Документация v1.11.8 и метаданные готовятся в изолированной ветке. Исторические замечания v1.11.7 сохранены в [чек-листе](RELEASE_CHECKLIST_v1.11.7.md).

## Метаданные

- Планируется `versionName = "1.11.8"`, `versionCode = 64`, tag `v1.11.8`.
- `applicationId = io.github.regstar2.tgwsproxy`; Android 8.0+ / `arm64-v8a`.
- Файлы только `TgWsProxy-Android-v1.11.8-arm64-v8a.apk` и `TgWsProxy-Android-v1.11.8-arm64-v8a.apk.sha256`.
- [Полное описание RU](v1.11.8.md) · [EN](v1.11.8_EN.md) · [Краткие notes](RELEASE_NOTES_v1.11.8.md) · [GO/NO-GO](RELEASE_CHECKLIST.md).

## Проверки и release gate

1. Завершить документационный PR и проверить diff, RU/EN и отсутствие секретов. Перевести документы в статус published **только после выпуска**.
2. На интегрированном `main` запустить проектный `scripts/ci.ps1`, аудит версии и проверку релизных метаданных. Тесты из предыдущего `main` / PR не заменяют final-head CI.
3. На Xiaomi проверить обновление из опубликованного v1.11.7 **точно подписанным v1.11.8 Release APK** (без удаления приложения): маршруты, Worker, AWG/WARP, MTProto, порт и настройки. Отдельно пройти отрицательные случаи updater, включая разрешения и повреждённый файл, состояние Quick Settings и занятый порт.
4. На доверенном Windows runner с оригинальной подписью выполнить `scripts/release.ps1 -Version v1.11.8`, проверить `aapt`, `apksigner` и фактический SHA-256. Секреты не сохранять в Git, Issue или release notes.
5. После GO **до создания tag** обновить полные RU/EN notes и краткий текст GitHub Release до фактически подтверждённых результатов: убрать пометки «не опубликован», «планируется» и NOT_RUN только там, где есть доказательства; исправить ссылочные статусы и перечень реальных assets. Последний commit этих финальных документов снова должен пройти CI. Только затем создать один аннотированный tag `v1.11.8` на проверенном commit. Не перезаписывать старые теги или существующие релизы.
6. Запустить [.github/workflows/release.yml](../../.github/workflows/release.yml) для **точного** tag. Workflow повторно проверяет release-source CI на hosted Windows и использует trusted Windows runner для подписания. Результаты проверяются после выполнения, а не предполагаются.
7. Скачать опубликованные APK и SHA-256; проверить digest и подписанную сборку, установить именно скачанный APK. После подтверждения обновить публичный статус README, release notes и индекс.

## Проверочные команды (из корня репозитория)

```powershell
.\scripts\ci.ps1
.\scripts\audit-release.ps1 -ExpectedVersion 1.11.8 -ExpectedVersionCode 64
.\scripts\release.ps1 -Version v1.11.8 -PreflightOnly
```

При отсутствии физического устройства, ключа подписи, финального PASS или доказательств сохранности настроек — **NO-GO**. Публикация не заменяет ручную приёмку критических Android-сценариев.
