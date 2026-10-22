# TgWsProxy Android v1.11.3

- `versionName 1.11.3` · `versionCode 59` · Android 8.0+ (`arm64-v8a`)
- Канал: **stable patch**

Добавлена системная плитка **TgWsProxy** для запуска и остановки существующего локального прокси без открытия приложения.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.3/docs/releases/v1.11.3.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.3/docs/releases/v1.11.3_EN.md)

## Главное

- Быстрое включение и выключение через Android Quick Settings, статус синхронизирован с реальной работой frontend.
- Упрощённая иконка, подписи на русском и английском языках, понятное сообщение при неподходящих настройках.
- Исправлено ложное `CONFIG_CHANGED`, из-за которого плитка отказывалась запускать уже настроенный прокси; добавлена защита от повторных быстрых нажатий.

## Установка

После публикации скачайте подписанный `TgWsProxy-Android-v1.11.3-arm64-v8a.apk` из Assets и установите поверх предыдущей официальной версии без удаления приложения. Требования: Android 8.0+ / ARM64. Плитка добавляется вручную через редактор системных быстрых настроек. Не изменяет настройки Telegram и не создаёт VPN.

## Проверено

[PR #110](https://github.com/Regstar2/tg-ws-proxy-android/pull/110): Windows CI и Worker transport regression — PASS; запуск/остановка через плитку подтверждены на Android 14 (Xiaomi/HyperOS). Проверка финального подписанного APK выполняется release workflow по тегу.

## Известные ограничения

Flowseal Legacy WebSocket остаётся экспериментальным; для первого запуска через плитку требуется ранее успешно сохранённая конфигурация. Доступность/вид плитки зависят от Android/OEM.

## Assets

- `TgWsProxy-Android-v1.11.3-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.3-arm64-v8a.apk.sha256`

[Сравнение v1.11.2...v1.11.3](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.2...v1.11.3)
