# TgWsProxy Android v1.11.6

`versionName 1.11.6` · `versionCode 62` · стабильное исправление · Android 8.0+ / ARM64 (`arm64-v8a`)

В версии v1.11.6 исправлена обработка истощения пула доменов Cloudflare Proxy и отмена незавершённых соединений при смене маршрута. Также изменён идентификатор Android-приложения, чтобы устранить конфликт установки с исходным проектом.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.6/docs/releases/v1.11.6.md) · [Английская версия](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.6/docs/releases/v1.11.6_EN.md)

## Изменения

- **Cloudflare Proxy:** при отсутствии доступных доменов повторные подключения завершаются контролируемым отказом без лишних TLS-соединений. Сохраняются ограничения повторных попыток и политика резервных маршрутов ([#125](https://github.com/Regstar2/tg-ws-proxy-android/issues/125), [PR #129](https://github.com/Regstar2/tg-ws-proxy-android/pull/129)).
- **MTProto:** при остановке прокси или смене маршрута отменяются незавершённые попытки подключения через Cloudflare. Диагностика старого соединения не смешивается с состоянием нового маршрута ([#126](https://github.com/Regstar2/tg-ws-proxy-android/issues/126), [PR #130](https://github.com/Regstar2/tg-ws-proxy-android/pull/130)).
- **Установка Android:** изменён `applicationId` на `io.github.regstar2.tgwsproxy`. Название `TgWsProxy` сохранено ([PR #131](https://github.com/Regstar2/tg-ws-proxy-android/pull/131)).

## Установка и обновление

После публикации скачайте `TgWsProxy-Android-v1.11.6-arm64-v8a.apk` из файлов релиза. Требуется Android 8.0+ и ARM64.

**Важное изменение:** из-за нового `applicationId` версия v1.11.6 устанавливается отдельно от старого приложения, а не обновляет его поверх прежней версии. Настройки, список Cloudflare Workers и AWG/WARP-профили автоматически не переносятся — их необходимо перенести или настроить заново. Не удаляйте прежнее приложение до сохранения необходимых конфигураций. Одновременный запуск двух локальных прокси на одном порту невозможен.

## Проверено

Автоматические проверки Windows CI, модульные тесты Android и регрессионные тесты транспорта Worker прошли при подготовке изменений. Установка и запуск подписанной отладочной сборки с новым идентификатором подтверждены. Проверка итогового подписанного релизного APK, его SHA-256 и установки из опубликованных файлов выполняется отдельно при выпуске.

## Известные ограничения

Выпускается только APK для ARM64. WebSocket-транспорт Flowseal Legacy остаётся экспериментальным. Дополнительная проверка совместимости установки отслеживается в [#128](https://github.com/Regstar2/tg-ws-proxy-android/issues/128).

## Файлы релиза

- `TgWsProxy-Android-v1.11.6-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.6-arm64-v8a.apk.sha256`

[История изменений](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.6/CHANGELOG.md) · [Сравнение v1.11.5 и v1.11.6](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.5...v1.11.6).
