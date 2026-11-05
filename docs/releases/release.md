# Release workflow — v1.11.7

**Состояние:** подготовка, публичный релиз и tag `v1.11.7` ещё не созданы. Последняя опубликованная стабильная версия — [v1.11.6](https://github.com/Regstar2/tg-ws-proxy-android/releases/tag/v1.11.6); план v1.11.7 — [milestone #11](https://github.com/Regstar2/tg-ws-proxy-android/milestone/11), основная работа — [Issue #135](https://github.com/Regstar2/tg-ws-proxy-android/issues/135) / [PR #137](https://github.com/Regstar2/tg-ws-proxy-android/pull/137).

## Метаданные

- `versionName = "1.11.7"`, `versionCode = 63`, tag `v1.11.7`.
- Приложение `io.github.regstar2.tgwsproxy`, namespace `com.amurcanov.tgwsproxy`, название `TgWsProxy`.
- Android 8.0+ / ABI `arm64-v8a`; только `TgWsProxy-Android-v1.11.7-arm64-v8a.apk` и `.apk.sha256` в публичных артефактах.
- [Полные RU notes](v1.11.7.md) · [EN notes](v1.11.7_EN.md) · [Краткий GitHub Release](RELEASE_NOTES_v1.11.7.md) · [Release checklist](RELEASE_CHECKLIST.md).
- На данный момент **неизвестны** SHA-256 и размер релизного APK; их нельзя подставлять из Debug.

## Требования до тега

1. Довести PR #137 до полной приёмки #135, проверить отдельные Wi-Fi/mobile политики, strict-only, порядок отключённых/включённых, миграцию v1 → v2, сброс и hot-apply. Не закрывать Issue, пока обязательные критерии не подтверждены.
2. Проанализировать [#138](https://github.com/Regstar2/tg-ws-proxy-android/issues/138): 11 801 локальных отказов Fake TLS за ~34 секунды в одном Xiaomi-логе. Нужны источник, повторяемость и влияние на ресурсы; определить, блокирует ли проблема стабильный релиз.
3. Проверить на последнем подписанном debug APK скорость MTProto при загрузке/отправке, время установки исходящего соединения и TCP → AWG вместе с VPN. Подтвердить Telegram текст/медиа и отсутствие скачущего уведомления. Прежние успешные логи на другом SHA — не итоговое доказательство.
4. Выполнить на текущем HEAD и затем на интегрированном `main` `scripts/ci.ps1`, `scripts/audit-release.ps1 -ExpectedVersion 1.11.7 -ExpectedVersionCode 63`, `scripts/release.ps1 -Version v1.11.7 -PreflightOnly`. GitHub Windows CI и Worker regression — SUCCESS на правильном SHA.
5. Проверить RU/EN ресурсы и текст, отсутствие приватных файлов, и что сохранившиеся настройки v1.11.6 не потерялись. Убедиться, что последний опубликованный v1.11.6 и целевой v1.11.7 используют одинаковый `applicationId`.
6. На доверенном Windows-окружении с **оригинальным релизным ключом** подготовить `scripts/release.ps1 -Version v1.11.7`. Проверить `aapt`, `apksigner`, SHA-256 и `dist/`. Скрипт очищает **только `dist/`** перед сборкой; не хранить секреты в репозитории.
7. Установить собранный **Release APK** поверх v1.11.6 с сохранением данных; проверить пакет/версию, экран маршрутов, запуск, двустороннюю передачу и уведомление. Не выполнять `adb uninstall` или `pm clear` для этой проверки.
8. Зафиксировать точный проверенный commit итогового `main`. Перед тегированием удостовериться, что `v1.11.7` не существует в удалённом Git и нет уже опубликованного GitHub Release с таким tag.

## Сборка и публикация

Выполнять **только после** полного PASS [чек-листа](RELEASE_CHECKLIST.md).

- Релиз создаётся из одного проверенного commit `main`, на который ставится аннотированный tag `v1.11.7` без перезаписи существующих тегов.
- [Release workflow](../../.github/workflows/release.yml) разрешён только владельцу `Regstar2`; checkout делает точный tag, запускает `scripts/ci.ps1`, восстанавливает signing key из GitHub Actions secrets и вызывает `scripts/release.ps1 -Version v1.11.7`. Проверить наличие доверенного self-hosted Windows runner и release secrets до запуска.
- `scripts/release.ps1` проверяет метаданные Android APK, подпись и создаёт два файла в `dist/`: APK и `apk.sha256`. При выпуске используется `RELEASE_NOTES_v1.11.7.md`; ссылки на полные русскую и английскую версии должны вести на **tag**, не на `main`.
- Никогда не включать в release APK или Assets debug-сборки, пользовательские runtime-логи, приватные AWG-конфигурации, keystore, токены или локальные правила проекта.
- Если CI, сертификат, доступность runner, подпись, device smoke или анализ #138 не подтверждены — **остановить выпуск** и оставить tag не созданным. Не заменять release gate хорошими показателями другого commit.

## После публикации

Сверить на GitHub tag SHA и release assets; скачать оба файла, пересчитать SHA-256 и установить **точно опубликованный APK**. Проверить запуск/остановку прокси, сохранённую политику, TCP → AWG и реальное получение/отправку данных Telegram. Обновление с v1.11.6 должно сохранять данные; для пакетов до изменения application ID такой перенос не гарантируется. Закрыть [milestone #11](https://github.com/Regstar2/tg-ws-proxy-android/milestone/11) только после завершения требований.

## Команды проверки (локально, в корне проекта)

```powershell
.\scripts\ci.ps1
.\scripts\audit-release.ps1 -ExpectedVersion 1.11.7 -ExpectedVersionCode 63
.\scripts\release.ps1 -Version v1.11.7 -PreflightOnly
```

Для сборки подписанного release-артефакта при доступном исходном keystore и настроенных переменных подписи: `.\scripts\release.ps1 -Version v1.11.7`. Публикацию тега и GitHub Release выполнять отдельно только после принятия решения GO.
