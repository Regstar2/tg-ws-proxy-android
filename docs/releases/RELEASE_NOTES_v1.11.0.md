# TgWsProxy Android v1.11.0

- `versionName 1.11.0`
- `versionCode 54`
- channel: **stable**

`v1.11.0` makes the userspace AWG/WARP route and in-app Consumer WARP profile provisioning part of the stable release.

[Полное описание на русском](../../blob/v1.11.0/docs/releases/v1.11.0.md) · [Full release notes in English](../../blob/v1.11.0/docs/releases/v1.11.0_EN.md)

## Главное

- Telegram может использовать `awg_warp` без root, Android `VpnService` и system TUN.
- Автоматическое создание профиля использует порядок: рабочий AWG/WARP-профиль → direct Consumer API → custom provisioning Workers → optional built-in pool.
- В приложение встроены **3 project provisioning Worker**. Они используются только для генерации/активации WARP-профилей, не передают Telegram traffic и не попадают в обычный `cf_worker_ws` Worker Pool.
- Custom provisioning Workers настраиваются отдельно; built-in provisioning можно полностью отключить.
- Проверка профиля требует реальный Telegram MTProto `req_pq_multi → resPQ` до принятия автоматического профиля.
- Сохранённые профили можно переименовывать и редактировать; автоматические имена идут как `WARP 1`, `WARP 2` и далее.

## Установка

После публикации скачайте `TgWsProxy-Android-v1.11.0-arm64-v8a.apk` из Assets. Поддерживается Android 8.0+ / `arm64-v8a`.

Обновление предназначено для установки поверх подписанных `v1.10.14` и `v1.11.0-beta.1` без удаления приложения. Финальный upgrade smoke выполняется перед stable-тегом.

## Worker

Актуальный гайд разделяет Telegram Worker и provisioning-only Worker: [docs/cloudflare-worker.md](../../blob/v1.11.0/docs/cloudflare-worker.md).

## Assets

Release workflow публикует:

- `TgWsProxy-Android-v1.11.0-arm64-v8a.apk`
- `TgWsProxy-Android-v1.11.0-arm64-v8a.apk.sha256`

Фактический SHA-256 находится в checksum asset.
