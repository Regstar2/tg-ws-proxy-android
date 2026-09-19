# TgWsProxy Android v1.11.0

- `versionName 1.11.0`
- `versionCode 54`
- channel: **stable**

`v1.11.0` makes the userspace AWG/WARP route and in-app Consumer WARP profile provisioning part of the stable release.

[Полное описание на русском](../../blob/v1.11.0/docs/releases/v1.11.0.md) · [Full release notes in English](../../blob/v1.11.0/docs/releases/v1.11.0_EN.md)

## Главное

- Telegram can use `awg_warp` without root, Android `VpnService` or a system TUN.
- Automatic profile creation now tries an existing validated AWG/WARP profile, direct Consumer API, custom provisioning Workers and then the optional built-in pool.
- The app includes **3 built-in project provisioning Workers**. They are used only to generate/activate WARP profiles and never carry Telegram traffic or enter the normal `cf_worker_ws` Worker Pool.
- Custom provisioning Workers can be added and checked separately; built-in provisioning can be disabled completely.
- Profile validation requires a real Telegram MTProto `req_pq_multi → resPQ` round-trip before an automatic profile is accepted.
- Saved profiles can be renamed and their config edited; generated names increment as `WARP 1`, `WARP 2`, and so on.

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
