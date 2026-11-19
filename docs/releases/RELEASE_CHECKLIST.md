# Release checklist — v1.11.10

**PREPARATION / NO-GO.** Последний опубликованный релиз — [v1.11.9](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.9); подписанный v1.11.10/66 и точный tag CI не подтверждены.

## Содержимое и документация

- [x] Изменения PR #165, #166, #167, #169 и #170 объединены в main до подготовки 1.11.10.
- [x] Подготовлены метаданные 1.11.10 / 66, RU/EN docs, версия, changelog и новая инструкция подписанного Debug.
- [ ] Проверен итоговый diff, секреты, локализация, ссылки и историческая документация.

## Автоматические проверки и артефакты

- [ ] Финальный PR HEAD и merge commit: Windows CI, Go race/Worker, Kotlin unit/lint, ABI audit — PASS.
- [ ] scripts/audit-release.ps1 -ExpectedVersion 1.11.10 -ExpectedVersionCode 66 — PASS.
- [ ] scripts/release.ps1 -Version v1.11.10 -PreflightOnly — PASS.
- [ ] Подписаны исходным ключом три Release APK: armeabi-v7a, arm64-v8a, universal; для каждого создан SHA-256.
- [ ] Подтверждены packageName, versionName/versionCode, minSdk, ELF/JNA payload и сертификат каждого APK.
- [ ] Проверено обновление официального 1.11.9/65 на 1.11.10/66 без удаления настроек.

## Реальные устройства и маршруты

- [ ] Проверены Android CA trust roots, валидный WSS/443 CF DC2/DC4, IP fallback, Direct WS и оба Worker-транспорта.
- [ ] Некорректные CA и hostname отклонены в контролируемой TLS fixture; нет trust-all.
- [ ] Явный WS/80 default-off и безопасность plaintext описаны и проверены; нет скрытой смены route policy.
- [ ] MTProto reconnect из уведомления, SOCKS5 regression и копирование Telegram ссылок проверены.
- [ ] Сообщения и медиа Telegram, переключение Wi-Fi/mobile, контроль фактического backend проверены.
- [ ] ARMv7, ARM64, Universal и native/JNA проверены на подходящих физических устройствах; эмуляторные SIGSEGV/SIGABRT не помечены PASS.

## Публикация

- [ ] Принято явное решение GO по оставшимся рискам #158 и WS/80.
- [ ] Аннотированный v1.11.10 tag указывает на проверенный main SHA; тег не перезаписан.
- [ ] Exact-tag CI PASS, затем trusted signing и публикация ровно 3 APK + 3 checksum.
- [ ] После публикации скачаны assets, независимо проверены SHA-256 и сертификаты.
- [ ] README/index переведены из статуса кандидат в опубликован после фактической проверки.

**Не создавать тег v1.11.10 до успешной подписанной приёмки и решения GO.**
