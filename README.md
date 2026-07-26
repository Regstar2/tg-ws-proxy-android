# TgWsProxy Android

Android-прокси для Telegram. Приложение поднимает локальный прокси на
`127.0.0.1:1443`, а дальше ведёт трафик через Cloudflare Proxy, прямой
WebSocket Telegram, Cloudflare Worker или TCP fallback.

В текущей ветке основной режим - **MTProto Proxy**. SOCKS5/WebSocket оставлен
как режим совместимости на том же локальном порту.

## Текущее состояние

- MTProto через **Cloudflare Proxy** проверен вручную на мобильной сети и на
  Wi-Fi. Это сейчас главный рабочий сценарий.
- По умолчанию используется порт `1443`, чтобы Telegram сразу получал нормальную
  MTProto-ссылку.
- Worker Pool есть в настройках, диагностике и runtime, но пока работает
  слишком медленно. Я бы не делал его основным маршрутом до отдельной доводки.
- Логи, отчёты и UI скрывают MTProto secret, query-параметры и чувствительные
  домены там, где это возможно.

## Что изменилось после опубликованной GitHub-ветки

Локальная версия заметно ушла вперёд относительно прежнего `main` на GitHub
(`v1.8.4`). Изменения:

- Добавлен локальный **MTProto Proxy frontend**: генерация `t.me/proxy` /
  `tg://proxy` ссылок, хранение 16-байтового секрета, запуск через
  foreground service, отдельный статус runtime.
- MTProto теперь умеет идти через route backend, включая `cf_proxy_ws`.
  Именно `MTProto -> CF Proxy` сейчас проверен на телефоне.
- Добавлен Flowseal-слой **Fake TLS**: обычный `dd<secret>` и вариант
  `ee<secret><domain_hex>` с masking domain. Есть отдельная опция passthrough:
  если её включить, невалидный Fake TLS probe можно проксировать на настоящий
  masking domain.
- Перенесены и адаптированы идеи Flowseal для CF Proxy: список доменов,
  quality gate для обновления, cooldown, аккуратная работа с плохими upstream
  payload.
- Для direct WebSocket добавлен cooldown Telegram IP: если IP таймаутится, runtime
  быстрее уходит в CF/Worker и не долбит тот же адрес сразу снова.
- WS pool получил возрастную ротацию, чтобы после сна телефона или смены сети
  меньше оставалось полумёртвых соединений.
- Добавлен listener watchdog: сервис следит, что локальный listener жив, и
  поднимает его заново при сбое.
- Добавлена поддержка test DC (`dc >= 10000`, `/apiws_test`, `force_test_dc`) для
  диагностики.
- Настройки по умолчанию изменены под реальное мобильное поведение: MTProto
  включён как frontend, CF Proxy имеет приоритет, общий порт - `1443`.
- Главный экран стал короче: без legacy compatibility, без кнопки Worker Pool,
  без раскрытия подробностей. Worker Pool не показывается в основной карточке
  состояния.
- Уведомление и диагностика показывают фактический frontend и route backend:
  например `MTProto Proxy · Cloudflare Proxy`, а не старые пустые значения.

## Скриншоты

<p align="center">
  <img src="docs/assets/screenshots/screenshot-main.jpg" width="220" alt="Главный экран" />
  <img src="docs/assets/screenshots/screenshot-settings-all.jpg" width="220" alt="Настройки" />
  <img src="docs/assets/screenshots/screenshot-settings-connection.jpg" width="220" alt="Настройки подключения" />
  <img src="docs/assets/screenshots/screenshot-settings-routes.jpg" width="220" alt="Политика маршрутов" />
  <img src="docs/assets/screenshots/screenshot-settings-cloudflare.jpg" width="220" alt="Cloudflare Proxy" />
  <img src="docs/assets/screenshots/screenshot-settings-app.jpg" width="220" alt="Настройки приложения" />
  <img src="docs/assets/screenshots/screenshot-settings-logs.jpg" width="220" alt="Настройки логов" />
</p>

## Режимы локального прокси

| Режим | Для чего |
|-------|----------|
| MTProto Proxy | Основной режим. Telegram подключается по MTProto proxy link. |
| SOCKS5 / WebSocket | Режим совместимости. Можно настроить в Telegram вручную как SOCKS5. |

Оба режима используют один локальный порт по умолчанию: `127.0.0.1:1443`.
Если порт занят ByeDPI или другим локальным прокси, его можно поменять в
настройках подключения.

## Маршруты

| Route kind | Что делает |
|------------|------------|
| `cf_proxy_ws` | WebSocket через Cloudflare Proxy domains (`kws{dc}.<domain>/apiws`). |
| `direct_ws` | Прямой WebSocket к Telegram (`kws{dc}.web.telegram.org`). |
| `cf_worker_ws` | WebSocket через ваш Cloudflare Worker. Код есть, но Worker Pool сейчас медленный. |
| `tcp_fallback` | Прямой TCP к IP датацентра Telegram на `:443`. |

WebSocket - это транспорт, а не route kind. В UI и логах route kind пишется
явно: `cf_proxy_ws`, `direct_ws`, `cf_worker_ws`, `tcp_fallback`.

## Настройки по умолчанию

| Сеть | Политика |
|------|----------|
| Мобильная | Только `cf_proxy_ws`, без fallback. |
| Wi-Fi | `cf_proxy_ws` в приоритете, затем `direct_ws` и `tcp_fallback`. |
| Неизвестная сеть | Как мобильная: только `cf_proxy_ws`. |

Сохранённые пользовательские политики не перетираются. Миграция применяется
только там, где пользователь не менял маршруты вручную.

## Fake TLS и masking domain

Без masking domain Telegram получает обычный MTProto secret вида
`dd<32 hex chars>`.

Если указать masking domain, ссылка строится как
`ee<secret><domain_hex>`. Так MTProto-подключение выглядит ближе к обычному TLS
соединению на выбранный домен.

Опция **Mask probes via masking domain** делает ещё один шаг: невалидный Fake TLS
ClientHello не закрывается сразу, а может быть отправлен на настоящий masking
domain. Включайте это только с доменом, которому доверяете: приложение начнёт
делать реальные исходящие соединения к этому хосту.

## Диагностика

В приложении есть:

- Route diagnostics для Direct, CF Proxy, Worker и TCP;
- отдельные блоки для SOCKS5/WS и MTProto;
- счётчики Fake TLS accepted/rejected/probe/passthrough;
- экспорт отчёта с маскированием secret, токенов и URL query;
- постоянные runtime-логи для коротких отладочных сессий; по умолчанию
  сохранение и runtime-сбор выключены.

Полезные строки в logcat с тегом `TgWsProxy`:

| Строка | Что смотреть |
|--------|--------------|
| `MTProto route truth` | selected/actual backend, fallback, DC и media flag. |
| `cfproxy connected` | домен CF Proxy и успешное подключение. |
| `direct_ws_ip_timeout` | direct IP ушёл в cooldown. |
| `fake_tls_*` | статистика Fake TLS и probe-сценариев. |
| `route_policy network=...` | активная политика для текущей сети. |

## Быстрый старт

1. Соберите или установите APK.
2. Запустите TgWsProxy.
3. Оставьте frontend **MTProto Proxy** и порт `1443`.
4. Нажмите **Применить в Telegram**.
5. Если Telegram висит на подключении, откройте диагностику и проверьте
   `cf_proxy_ws`.

Для ручного SOCKS5-режима в Telegram:

```text
Host: 127.0.0.1
Port: 1443
Username/password: пусто
```

## Сборка

Нужно: JDK 17, Android SDK, Go, Gradle wrapper из репозитория.

```powershell
.\gradlew.bat assembleDebug
```

APK появится здесь:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Go runtime отдельно:

```powershell
cd native\tgwsproxy
go test ./...
```

Скрипт сборки APK с копированием в локальный `artifacts/`:

```powershell
.\scripts\build-apk.ps1 -Configuration Debug
```

`artifacts/`, `runtime-logs/`, keystore-файлы и локальные env-файлы не входят в
репозиторий.

## Структура

| Путь | Что внутри |
|------|------------|
| `app/` | Android UI, настройки, `ProxyService`, bridge к native runtime. |
| `native/tgwsproxy/` | Go runtime, MTProto frontend, маршруты и тесты. |
| `docs/` | Архитектура, release notes, ручные чеклисты. |
| `scripts/` | Сборка native/APK и Cloudflare Worker script. |

Подробнее: [docs/development/repository-structure.md](docs/development/repository-structure.md)

## Документация

- [docs/architecture/architecture.md](docs/architecture/architecture.md)
- [docs/architecture/CF_DOMAIN_POOL.md](docs/architecture/CF_DOMAIN_POOL.md)
- [docs/architecture/cloudflare-worker.md](docs/architecture/cloudflare-worker.md)
- [docs/testing/manual-checklist-1.8.md](docs/testing/manual-checklist-1.8.md)
- [docs/releases/release.md](docs/releases/release.md)
- [CHANGELOG.md](CHANGELOG.md)

## Использование нейросетей

При работе над проектом использовались нейросети: для отдельных участков кода,
тестов и документации. Итоговые изменения проверялись вручную.

## Происхождение

- [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) - desktop
  runtime и основная идея маршрутов.
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)
  - Android-обёртка, от которой пошёл этот проект.
- [Regstar2/TgWsProxy_Android](https://github.com/Regstar2/TgWsProxy_Android)
  - текущая Android-ветка.

Лицензия: [GPLv3](LICENSE).
