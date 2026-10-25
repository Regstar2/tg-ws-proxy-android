# TgWsProxy Android v1.11.4

`versionName 1.11.4` · `versionCode 60` · **stable patch** · Android 8.0+ / ARM64 (`arm64-v8a`)

Исправления управления плиткой Quick Settings, foreground-уведомлениями и первоначальной инструкцией без изменения транспорта прокси.

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.4/docs/releases/v1.11.4.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.4/docs/releases/v1.11.4_EN.md)

## Главное

- Долгое нажатие на плитку теперь направляется в главное окно приложения; реальное поведение зависит от SystemUI производителя.
- Уведомление работающего прокси больше не публикуется заново при неизменном содержимом; на Xiaomi / Android 14 проверены конкурирующие VPN-уведомления и действия прокси.
- Настройка «Не показывать снова» сохраняется после «Пропустить», «Готово» или системного закрытия инструкции.

## Установка и обновление

Скачайте после публикации `TgWsProxy-Android-v1.11.4-arm64-v8a.apk` из **Assets** и установите поверх предыдущей официальной версии без удаления приложения (требуется совпадение подписи). Для первого запуска настройте прокси в приложении; плитка не меняет настройки Telegram и не создаёт VPN. Встроенная проверка обновлений открывает GitHub Releases, но не устанавливает APK автоматически.

## Проверено

PR [#116](https://github.com/Regstar2/tg-ws-proxy-android/pull/116), [#117](https://github.com/Regstar2/tg-ws-proxy-android/pull/117), [#119](https://github.com/Regstar2/tg-ws-proxy-android/pull/119): относящиеся Windows CI/Android unit tests/Worker regressions — PASS. Уведомления и первоначальная инструкция проверены владельцем на Xiaomi / Android 14. **Реальный долгий тап на Xiaomi/HyperOS ещё не подтверждён**. Подпись и контрольная сумма финального APK проверяются release workflow по точному тегу.

## Известные ограничения

Долгое нажатие зависит от Android SystemUI; Flowseal Legacy WebSocket остаётся экспериментальным; запуск из плитки требует предварительно успешно сохранённой конфигурации. Подробности приведены в двуязычных заметках.

## Assets

- `TgWsProxy-Android-v1.11.4-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.4-arm64-v8a.apk.sha256`

[Сравнение с v1.11.3](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.3...v1.11.4)
