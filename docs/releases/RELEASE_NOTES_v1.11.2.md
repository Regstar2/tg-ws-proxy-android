# TgWsProxy Android v1.11.2

- `versionName 1.11.2` · `versionCode 58`
- Канал: **stable patch** (Flowseal Legacy — экспериментальный транспорт)

Восстановлена **экспериментальная поддержка оригинальных Flowseal Worker** через WebSocket `/apiws`. Переработан экран **Worker Pool**: единый список, выбор протокола, диагностика и более удобная настройка стратегий.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.2/docs/releases/v1.11.2.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.2/docs/releases/v1.11.2_EN.md)

## Главное

- Возвращён явный выбор **Flowseal Legacy WebSocket (/apiws)** и **HTTPS Chunk Relay**; существующие адреса сохраняют прежний Chunk Relay.
- Исправлены ложноположительные результаты Worker-проверки: WebSocket HTTP 101 не доказывает работу MTProto.
- Worker Pool получил единый список адресов, удобные действия, локализацию, читаемые длинные домены и обновление стратегии без повторного открытия вкладки.

## Важное ограничение

**Flowseal Legacy нестабилен и экспериментален.** В некоторых сетях Telegram может не подключиться, сообщения и медиа могут не загружаться из-за ограничений долгоживущего Cloudflare WebSocket. Даже успешная проверка протокола не гарантирует передачу MTProto. HTTPS Chunk Relay также может работать медленно; Worker-маршрут не рекомендуется как основной.

## Установка

Android 8.0+ / `arm64-v8a`. Скачайте подписанный `TgWsProxy-Android-v1.11.2-arm64-v8a.apk` из Assets, установите поверх предыдущей официальной сборки без удаления приложения.

## Проверки

PR #106: Windows CI и Worker transport regression — успешно. Финальный подписанный APK, версия и SHA-256 проверяются release workflow из точного тега.

## Assets

- `TgWsProxy-Android-v1.11.2-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.2-arm64-v8a.apk.sha256`
