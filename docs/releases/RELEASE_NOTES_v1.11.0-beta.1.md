# TgWsProxy Android v1.11.0-beta.1

**Русский** · [English](RELEASE_NOTES_v1.11.0-beta.1_EN.md)

## Метаданные релиза

- `versionName 1.11.0-beta.1`
- `versionCode 53`

## О релизе

`v1.11.0-beta.1` — публичная beta-версия нового userspace AWG/WARP-маршрута и встроенного управления WARP/AWG-профилями. Версия предназначена для проверки нового маршрута на большем числе Android-устройств и сетей до выпуска `v1.11.0` stable.

Обновление совместимо с текущей конфигурацией приложения. AWG/WARP остаётся дополнительным маршрутом и не заменяет существующие Cloudflare Proxy / Worker / direct пути. Приложение по-прежнему не создаёт Android `VpnService` и не перенаправляет весь трафик устройства.

## Главное

- Добавлен `awg_warp`: Telegram-трафик может идти через userspace AmneziaWG/WARP без root, системного TUN и Android `VpnService`.
- В **Настройки → Cloudflare → WARP / AmneziaWG** можно автоматически создать Consumer WARP-профиль, импортировать `.conf`, проверить, выбрать и удалить профиль.
- Автоматическое создание использует локальную keypair, bounded autotune и сохраняет профиль только после двух успешных full-duplex проверок.
- При недоступности прямого Consumer WARP API приложение может использовать ограниченный Worker bootstrap; произвольный HTTP proxy при этом не открывается.

## Изменения

### Добавлено

- Multi-profile app-private storage для WARP/AWG-конфигураций с отдельным `selectedProfileId`.
- Автоматическая Consumer WARP registration/activation и формирование AWG-конфига.
- Строгая network validation: свежий handshake, tunnel TX/RX и реальный application downstream через userspace AWG transport.
- Bounded autotune до 16 кандидатов по endpoint и `Jc` / `Jmin` / `Jmax` / `I1`; прошедший кандидат подтверждается повторной full-duplex проверкой (`2/2`).
- Экран профилей, экран создания и экран деталей профиля в Cloudflare settings.
- Ручной импорт `.conf` как fallback.
- Restricted Worker bootstrap для `api.cloudflareclient.com`, когда прямой Android TLS-доступ к Consumer WARP API недоступен.

### Изменено

- HTTP `429` при WARP registration больше не вызывает серию быстрых повторных POST-запросов.
- При временной недоступности новой регистрации autotune может использовать уже сохранённый automatic Consumer WARP registration seed без повторной передачи private key наружу.
- `PrivateKey` скрыт в UI по умолчанию и не включается в support-safe diagnostics/logs.

## Установка и обновление

### Новая установка

После публикации скачайте `TgWsProxy-Android-v1.11.0-beta.1-arm64-v8a.apk` из GitHub Releases. Поддерживается Android 8.0+ и ABI `arm64-v8a`.

### Обновление

Планируемый путь обновления — установка beta APK поверх `v1.10.14` той же release-подписи. Настройки и существующие профили приложения должны сохраниться.

Финальный signed-APK upgrade smoke для самого release-артефакта должен быть выполнен до создания тега. Если Android сообщает о несовместимой подписи, не удаляйте приложение без учёта того, что uninstall удаляет app-private настройки.

Встроенный экран Updates проверяет официальный GitHub Releases feed и открывает страницу релиза; приложение не выполняет silent self-update.

## Проверено

| Проверка | Среда | Результат |
|---|---|---|
| PR CI для реализации #84 | GitHub-hosted Windows + native/Worker tests | Успешно |
| Automatic Consumer WARP registration + activation | Android 14, Xiaomi 11 Lite 5G NE | Успешно |
| Bounded AWG autotune | Android 14 | Кандидат подтверждён `2/2` |
| Telegram MTProto через `actual_backend=awg_warp` | Android 14 | Успешно, `fallback_used=false` |
| Двусторонний Telegram traffic и media | Android 14 | Успешно |
| Финальный signed APK из тега | — | Ещё не проверено; обязательный pre-tag gate |
| Обновление release APK поверх v1.10.14 | — | Ещё не проверено; обязательный pre-tag gate |

## Известные проблемы

- Consumer WARP registration использует внешний stability-sensitive API; его доступность и rate limits контролируются не приложением.
- На целевой Android/Wi-Fi сети прямой TLS к `api.cloudflareclient.com` ранее зависал, поэтому для fresh provisioning может потребоваться Worker bootstrap.
- Использование уже существующего AWG/WARP-туннеля для получения новой независимой Consumer WARP registration без Worker вынесено в #86 и не входит в эту beta.
- `awg_warp` проверен на ограниченном числе устройств/сетей; beta нужна для расширения совместимости перед stable.

## Артефакты

Release workflow должен опубликовать ровно два файла:

- `TgWsProxy-Android-v1.11.0-beta.1-arm64-v8a.apk` — подписанный Android APK;
- `TgWsProxy-Android-v1.11.0-beta.1-arm64-v8a.apk.sha256` — SHA-256 checksum.

Фактические размер и SHA-256 фиксируются только после сборки release workflow.

## Ссылки

- [CHANGELOG](../../CHANGELOG.md)
- [Issue #84](../../issues/84)
- [Issue #86](../../issues/86)
- [Предыдущий релиз v1.10.14](../../releases/tag/v1.10.14)
