# TgWsProxy Android v1.11.10 — TLS, WS/80 и поддержка ARMv7

versionName 1.11.10 · versionCode 66 · кандидат · Android 8.0+ · ARMv7 / ARM64 / Universal

Версия исправляет MTProto reconnect и копирование ссылок, добавляет native TLS verification и поддержку ARMv7/ARM64/Universal APK.

[Полные заметки на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.10/docs/releases/v1.11.10.md) · [English notes](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.10/docs/releases/v1.11.10_EN.md)

## Главное

- Исправлено переподключение из уведомления (PR #165).
- Главное окно копирует ссылку MTProto/SOCKS5 (PR #166).
- Native WebSocket/Worker HTTPS проверяют upstream TLS-сертификаты (PR #167).
- **CF Proxy без TLS включён по умолчанию** при отсутствии сохранённого выбора. Этот WS/80 **не шифрует внешний участок и не проверяет подлинность сервера**; выключите настройку для WSS/443 (PR #169).
- Выпускаются три ABI варианта с отдельными SHA-256 (PR #170).

## Приёмка

Отдельные PR проходили CI и тесты Xiaomi. Итоговые подписанные v1.11.10/66, Android CA, новый default WS/80 и exact-tag CI пока не подтверждены. После публикации используйте [официальные APK](https://github.com/Regstar2/tg-ws-proxy-android/releases) с .sha256 и устанавливайте поверх v1.11.9 без удаления приложения.
