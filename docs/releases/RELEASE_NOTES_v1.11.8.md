# TgWsProxy Android v1.11.8 — обновления из приложения и исправления стабильности

`versionName 1.11.8` · `versionCode 64` · stable patch · Android 8.0+ / ARM64 (`arm64-v8a`)

v1.11.8 добавляет проверяемое скачивание и запуск установки APK из приложения, разделяет ошибки входящего Fake TLS и успешные MTProto-маршруты, исправляет плитку Quick Settings и сообщение о занятом порту.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.8/docs/releases/v1.11.8.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.8/docs/releases/v1.11.8_EN.md)

## Главное

- **Обновления:** официальный APK скачивается в приложении, проверяется по SHA-256, подписи и совместимости и передаётся системному установщику после согласия пользователя ([#139](https://github.com/Regstar2/tg-ws-proxy-android/issues/139)).
- **Fake TLS:** отклонённые локальные входы не стирают состояние успешного исходящего маршрута; повторяющиеся логи ограничены, счётчики сохранены ([#138](https://github.com/Regstar2/tg-ws-proxy-android/issues/138)).
- **Quick Settings:** отображение плитки и действие нажатия согласованы по состоянию frontend ([#146](https://github.com/Regstar2/tg-ws-proxy-android/issues/146)).
- **Порт занят:** при подтверждённом конфликте выводится краткая подсказка изменить порт ([#148](https://github.com/Regstar2/tg-ws-proxy-android/issues/148)).

## Установка

Скачайте `TgWsProxy-Android-v1.11.8-arm64-v8a.apk` и парный `.sha256` из файлов релиза. Android 8.0+ / ARM64. При обновлении поверх v1.11.7 не удаляйте приложение: требуется прежняя подпись и проверка сохранности настроек. Сборки с прежним `com.amurcanov.tgwsproxy` не обновляются поверх нового пакета.

## Проверено

CI `main` на `ceefbc2f`: [PASS](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43223479710). Локальные Android unit tests, lint, debug/androidTest APK: **PASS** (instrumentation на устройстве не запускалась). Подписанный локальный Release APK `1.11.8/64` собран; сертификат и APK Signature Scheme v2 проверены. Основной Xiaomi-путь обновления подписанный debug → v1.11.7 и нагрузочный Fake TLS smoke были подтверждены ранее. **Владелец 12.11.2026 сообщил, что ручные проверки v1.11.8, в том числе обновления и работы приложения, прошли успешно; отдельный протокол сценариев не предоставлен.** CI точного тега и APK из GitHub Releases проверяются отдельно в штатном процессе публикации.

## Известные ограничения

Первопричина спонтанных Fake TLS reconnect 5 ноября не определена; отдельная защита проверена. Часть отрицательных сценариев in-app update не имеет device evidence; Flowseal Legacy WebSocket экспериментален. Только ARM64.

## Артефакты

Артефакты GitHub Release: `TgWsProxy-Android-v1.11.8-arm64-v8a.apk` и `TgWsProxy-Android-v1.11.8-arm64-v8a.apk.sha256`. Локальный подписанный кандидат собран, его SHA-256: `1d4e9ae194e5043a949966a1ed07fe37c0a72865ca2fcca89aeb1e852b059746` (сертификат SHA-256: `ae55265059933a7b495bd57bc571d294a1850609f56e89117eddf682faae2c6d`). **Этот hash не следует выдавать за hash будущего опубликованного APK:** после exact-tag build и публикации требуется проверка фактических GitHub assets.

[CHANGELOG](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.8/CHANGELOG.md) · [Сравнение с v1.11.7](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.7...v1.11.8)
