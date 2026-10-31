# TgWsProxy Android v1.11.6

`versionName 1.11.6` · `versionCode 62` · stable patch · Android 8.0+ / ARM64

В версии v1.11.6 исправлено поведение CF Proxy при исчерпании доступных доменов и при переключении маршрутов. Android-приложение теперь использует отдельный идентификатор пакета.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.6/docs/releases/v1.11.6.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.6/docs/releases/v1.11.6_EN.md)

## Главное

- CF pool exhaustion и массовые reconnect ограничены, отказ `cfproxy_unavailable` диагностируется явно ([#125](https://github.com/Regstar2/tg-ws-proxy-android/issues/125), PR #129).
- Незавершённые CF TLS-попытки отменяются при stop/reconfigure, события разделены по route generation ([#126](https://github.com/Regstar2/tg-ws-proxy-android/issues/126), PR #130).
- Android `applicationId`: `io.github.regstar2.tgwsproxy` (PR #131). Название `TgWsProxy` не меняется.

## Установка

Скачайте `TgWsProxy-Android-v1.11.6-arm64-v8a.apk` из Assets после публикации. Требуется ARM64 и Android 8.0+. **Внимание:** новый package устанавливается отдельно от старого; настройки и AWG/WARP-профили автоматически не переносятся.

## Проверено

Windows CI, Android unit tests, Worker regression и установка подписанного Debug APK проверены на этапе подготовки. Проверки подписанного Release APK, его SHA-256 и установки из Assets выполняются при выпуске; не следует считать Debug APK релизным.

## Ограничения

Только ARM64; два локальных прокси не могут слушать один порт одновременно. Flowseal Legacy WebSocket остаётся экспериментальным. Дополнительная проверка совместимости установки: [#128](https://github.com/Regstar2/tg-ws-proxy-android/issues/128).

## Assets

- `TgWsProxy-Android-v1.11.6-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.6-arm64-v8a.apk.sha256`

[CHANGELOG](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.6/CHANGELOG.md) · [Сравнение v1.11.5...v1.11.6](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.5...v1.11.6).
