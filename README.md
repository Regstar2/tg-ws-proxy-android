<div align="center">

# TgWsProxy Android

### Локальный MTProto-прокси для Telegram с управляемой маршрутизацией

Приложение поднимает прокси на Android и направляет трафик Telegram через Cloudflare Proxy, прямой WebSocket, собственный Worker или TCP fallback.

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#сборка)
[![Go runtime](https://img.shields.io/badge/runtime-Go-00ADD8?logo=go&logoColor=white)](#архитектура)
[![Release](https://img.shields.io/github/v/release/Regstar2/TgWsProxy_Android?display_name=tag)](../../releases)
[![License](https://img.shields.io/badge/license-GPLv3-blue)](LICENSE)

[Быстрый старт](#быстрый-старт) · [Маршруты](#маршруты) · [Диагностика](#диагностика) · [Документация](#документация)

</div>

---

<p align="center">
  <img src="docs/assets/screenshots/screenshot-main.jpg" width="210" alt="Главный экран TgWsProxy" />
  <img src="docs/assets/screenshots/screenshot-settings-connection.jpg" width="210" alt="Настройки подключения" />
  <img src="docs/assets/screenshots/screenshot-settings-routes.jpg" width="210" alt="Политика маршрутов" />
  <img src="docs/assets/screenshots/screenshot-settings-cloudflare.jpg" width="210" alt="Cloudflare Proxy" />
</p>

## Что это

TgWsProxy превращает Android-устройство в локальную точку подключения для Telegram. Telegram соединяется с `127.0.0.1:1443`, а приложение выбирает фактический транспорт в зависимости от сети и настроенной политики.

```text
Telegram
   │ MTProto proxy link
   ▼
127.0.0.1:1443
   │
   ├── Cloudflare Proxy WebSocket   ← основной проверенный маршрут
   ├── Direct Telegram WebSocket
   ├── Cloudflare Worker WebSocket
   └── Direct TCP fallback
```

> [!IMPORTANT]
> Основной рабочий сценарий проекта — **MTProto → Cloudflare Proxy**. Worker Pool реализован, но пока не рекомендуется как маршрут по умолчанию из-за задержек.

## Быстрый старт

1. Установите APK из [GitHub Releases](../../releases).
2. Откройте приложение и оставьте frontend **MTProto Proxy**.
3. Проверьте локальный порт `1443`.
4. Нажмите **Применить в Telegram**.
5. При проблемах откройте диагностику и проверьте `cf_proxy_ws`.

Для режима совместимости SOCKS5:

```text
Host: 127.0.0.1
Port: 1443
Username: пусто
Password: пусто
```

## Ключевые возможности

<table>
<tr>
<td width="50%" valign="top">

### Маршрутизация

- разные политики для Wi-Fi и мобильной сети;
- Cloudflare Proxy, Direct WS, Worker и TCP;
- cooldown для проблемных Telegram IP;
- возрастная ротация WebSocket-соединений;
- автоматическое восстановление локального listener.

</td>
<td width="50%" valign="top">

### MTProto и Fake TLS

- локальный MTProto frontend;
- генерация `t.me/proxy` и `tg://proxy` ссылок;
- обычный `dd<secret>`;
- Fake TLS `ee<secret><domain_hex>`;
- опциональный passthrough на masking domain.

</td>
</tr>
<tr>
<td width="50%" valign="top">

### Диагностика

- отдельные проверки каждого route backend;
- фактический frontend и выбранный маршрут;
- счётчики Fake TLS;
- runtime-логи для коротких сессий;
- экспорт отчёта.

</td>
<td width="50%" valign="top">

### Защита данных

- маскирование MTProto secret;
- очистка токенов и query-параметров;
- сохранение логов выключено по умолчанию;
- локальные keystore и env-файлы исключены из Git.

</td>
</tr>
</table>

## Маршруты

| Route kind | Назначение | Рекомендация |
|---|---|---|
| `cf_proxy_ws` | WebSocket через Cloudflare Proxy domains | Основной маршрут |
| `direct_ws` | Прямой WebSocket к Telegram | Дополнительный маршрут для Wi-Fi |
| `cf_worker_ws` | WebSocket через собственный Cloudflare Worker | Экспериментальный |
| `tcp_fallback` | Прямой TCP к Telegram DC на `:443` | Резервный маршрут |

### Политика по умолчанию

| Сеть | Порядок |
|---|---|
| Мобильная | `cf_proxy_ws`, без fallback |
| Wi-Fi | `cf_proxy_ws` → `direct_ws` → `tcp_fallback` |
| Неизвестная | как мобильная сеть |

Пользовательские политики сохраняются и не перезаписываются миграцией.

## Fake TLS

Без masking domain используется MTProto secret:

```text
dd<32 hex chars>
```

С masking domain:

```text
ee<secret><domain_hex>
```

> [!WARNING]
> Опция **Mask probes via masking domain** создаёт реальные исходящие соединения к выбранному домену. Используйте только домен, которому доверяете.

## Диагностика

В отчёте и `logcat` полезны следующие события:

| Событие | Значение |
|---|---|
| `MTProto route truth` | выбранный и фактический backend, fallback, DC |
| `cfproxy connected` | успешное подключение к Cloudflare Proxy |
| `direct_ws_ip_timeout` | Telegram IP помещён в cooldown |
| `fake_tls_*` | принятые и отклонённые Fake TLS запросы |
| `route_policy network=...` | активная политика текущей сети |

```powershell
adb logcat -s TgWsProxy
```

## Архитектура

```text
app/
├── Android UI
├── settings and route policy
├── foreground ProxyService
└── bridge to native runtime

native/tgwsproxy/
├── MTProto frontend
├── SOCKS5 compatibility frontend
├── WebSocket and TCP routes
├── Fake TLS
└── tests
```

Android отвечает за UI, жизненный цикл сервиса и настройки. Go runtime реализует локальные frontend-протоколы, соединения и фактическую маршрутизацию.

## Сборка

Требования: JDK 17, Android SDK, Go и Gradle Wrapper из репозитория.

```powershell
.\gradlew.bat assembleDebug
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Тесты Go runtime:

```powershell
cd native\tgwsproxy
go test ./...
```

## Документация

- [Архитектура](docs/architecture/architecture.md)
- [Пул Cloudflare-доменов](docs/architecture/CF_DOMAIN_POOL.md)
- [Cloudflare Worker](docs/architecture/cloudflare-worker.md)
- [Структура репозитория](docs/development/repository-structure.md)
- [Ручной чек-лист](docs/testing/manual-checklist-1.8.md)
- [Подготовка релиза](docs/releases/release.md)
- [История изменений](CHANGELOG.md)

## Происхождение

Проект развивает идеи и код из:

- [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy);
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android).

При разработке отдельных участков кода, тестов и документации использовались нейросети. Итоговые изменения проверялись вручную.

---

<div align="center">

**TgWsProxy Android** распространяется по лицензии [GNU GPLv3](LICENSE).

</div>
