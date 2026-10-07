# TgWsProxy Android v1.11.1

- `versionName 1.11.1`
- `versionCode 57`
- channel: **stable**

`v1.11.1` adds opt-in proxy autostart after Android boot while reusing the existing `ProxyService`, saved configuration, and foreground notification path.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.1/docs/releases/v1.11.1.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.1/docs/releases/v1.11.1_EN.md)

## Главное

- Добавлена настройка **«Автозапуск при включении устройства»**, выключенная по умолчанию.
- После `BOOT_COMPLETED` приложение может автоматически восстановить локальный прокси с последней сохранённой конфигурацией.
- Activity не открывается автоматически: запускается только существующий foreground `ProxyService`.
- Для SOCKS5 невалидная сохранённая runtime-конфигурация безопасно пропускается; поддерживаемые frontend без `last_runtime_ips` не блокируются искусственно.
- Повторная доставка boot broadcast не должна создавать второй proxy runtime.
- Добавлены privacy-safe `BOOT_*` события и unit-тесты boot/autostart policy.
- Реальный reboot smoke подтверждён на Xiaomi/HyperOS. На таких прошивках пользователю может потребоваться отдельно разрешить фоновый автозапуск TgWsProxy в системных настройках.

## Установка

Скачайте `TgWsProxy-Android-v1.11.1-arm64-v8a.apk` из Assets. Поддерживается Android 8.0+ / `arm64-v8a`.

APK предназначен для обычного обновления поверх подписанного `v1.11.0` при той же release-подписи.

## Assets

Release workflow публикует:

- `TgWsProxy-Android-v1.11.1-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.1-arm64-v8a.apk.sha256`

Фактический SHA-256 находится в checksum asset.
