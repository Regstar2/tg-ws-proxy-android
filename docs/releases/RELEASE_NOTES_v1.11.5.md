# TgWsProxy Android v1.11.5

`versionName 1.11.5` · `versionCode 61` · **stable patch** · Android 8.0+ / ARM64 (`arm64-v8a`)

Исправлен запуск прокси из системной плитки Quick Settings после смены Wi-Fi/мобильной сети. Ускорена реакция плитки; текущие сетевые настройки всегда применяются заново, без использования устаревшей конфигурации.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.5/docs/releases/v1.11.5.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.5/docs/releases/v1.11.5_EN.md)

## Исправлено

- **Quick Settings:** устранён ложный отказ «Настройки изменились» после переключения Wi-Fi ↔ мобильной сети или смены маршрута; плитка собирает актуальный runtime при каждом запуске ([#122](https://github.com/Regstar2/tg-ws-proxy-android/issues/122), [PR #123](https://github.com/Regstar2/tg-ws-proxy-android/pull/123)).
- **Безопасность конфигурации:** некорректные текущие настройки не подменяются старыми сохранёнными параметрами. Для самого первого запуска нужен один успешный старт из приложения.
- **Отклик интерфейса:** подготовка настроек больше не блокирует главный поток плитки; «Подключение» показывается сразу, ACTIVE — только при работающем прокси.
- **Диагностика:** добавлены безопасные события времени подготовки и передачи foreground-уведомления Android.

## Установка

Скачайте `TgWsProxy-Android-v1.11.5-arm64-v8a.apk` из Assets и установите поверх официальной v1.11.4 **без удаления приложения** (при совпадающей подписи). Обновление не создаёт VPN и не меняет настройки Telegram.

## Проверено

PR #123: Windows CI, Android unit tests/Debug build и Worker transport regression — PASS. На реальном Android-устройстве подтверждены запуск плиткой после смены сети и приемлемая скорость реакции на тестовой сборке с сохранением данных. Подпись, versionName/versionCode и SHA-256 **финального Release APK** проверяются workflow после push tag.

## Ограничения

Flowseal Legacy WebSocket остаётся экспериментальным; некоторые сети ограничивают длительные соединения. Для первого запуска плитки нужна ранее успешно настроенная конфигурация. Другая OEM/Android-матрица, AWG/SOCKS5 device smoke и установка окончательного APK не подтверждены.

## Assets

- `TgWsProxy-Android-v1.11.5-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.5-arm64-v8a.apk.sha256`

[Сравнение с v1.11.4](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.4...v1.11.5)
