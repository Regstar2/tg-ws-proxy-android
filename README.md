# TgWsProxy Android

Локальный SOCKS5-прокси для Telegram на Android. Приложение поднимает локальный прокси, принимает MTProto-сессии Telegram и перенаправляет поддерживаемый трафик через WebSocket/WSS. Основной практический сценарий текущей ветки - работа через Cloudflare Proxy на сетях, где прямые Telegram endpoint-ы недоступны.

Текущая версия рабочей ветки: `1.3.2` (CF domain pool polishing).

## Происхождение

Проект является форком Android-версии `tg-ws-proxy` и сохраняет атрибуцию исходным авторам:

- Runtime-идея и исходный проект: [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)
- Исходный Android-форк: [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)

Android-форк распространяется под GPLv3. Оригинальный runtime-проект Flowseal распространяется под MIT; соответствующая атрибуция сохранена.

## AI-assisted development

Изменения в этой рабочей ветке подготовлены при помощи Codex и GPT-5.4. Репозиторий остаётся форком и содержит код, идеи и лицензионные обязательства исходных проектов Flowseal и amurcanov; эта пометка не заменяет их атрибуцию.

## Что умеет приложение

- Локальный SOCKS5-прокси на `127.0.0.1`, порт по умолчанию `1081`.
- Быстрое применение прокси в Telegram через deep link.
- Foreground service для фоновой работы.
- Jetpack Compose UI с системной, светлой и тёмной темой.
- Выбор языка интерфейса: системный, русский, английский.
- Настройки DC/IP, Cloudflare-домена и режимов CF Proxy.
- Runtime-логи в приложении и экспорт логов в выбранную папку.
- Диагностическое логирование стадий `DNS -> TCP -> TLS -> WS`.

## Скриншоты

<p>
  <img src="docs/screenshots/main-screen.jpg" width="260" alt="Главный экран TgWsProxy">
  <img src="docs/screenshots/settings-screen.jpg" width="260" alt="Экран настроек TgWsProxy">
</p>

## Cloudflare Proxy

На мобильной сети текущий рабочий путь подтверждён через CF Proxy. Direct path до Telegram endpoint-ов на тестовых сетях часто недоступен или нестабилен, поэтому для реального использования сейчас рекомендуется режим `CF first` или `CF only`.

Режимы:

- `CF proxy` - включает Cloudflare fallback.
- `CF first` - сначала пробует `kws{dc}.<domain>`, затем direct WS, если CF недоступен.
- `CF only` - не использует direct Telegram upstream и direct TCP passthrough для Telegram-like трафика.
- `Worker only` - использует только Worker и тоже не допускает direct TCP passthrough для Telegram-like трафика.
- `Direct + fallback routes` - честное имя для цепочки `Direct -> Worker -> CF -> TCP`.

Домен по умолчанию: `pclead.co.uk`. Он взят из подхода Flowseal и удобен для проверки, но это общий endpoint. Cloudflare ограничивает одновременные WebSocket-подключения, поэтому домен по умолчанию может временно отдавать `429 Too Many Requests` или перестать работать. Для постоянного использования лучше настроить собственный Cloudflare-домен и указать его в настройках приложения.

Начиная с `v1.3.2`, ручной CF-домен больше не является единственной опорой. Android fork сохраняет поле для пользовательского домена, но также включает встроенный CF-domain pool. Если ручной домен возвращает `429`, `403`, `5xx` или повторно падает на timeout/TLS/WebSocket handshake, runtime временно переводит его в cooldown и пробует другой CF-домен либо следующий маршрут, разрешённый выбранным режимом.

Для собственного домена используются хосты вида:

```text
kws1.<domain>
kws2.<domain>
kws3.<domain>
kws4.<domain>
kws5.<domain>
kws203.<domain>
```

Ориентируйтесь на инструкцию Flowseal по Cloudflare Proxy: [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).

## Как пользоваться

1. Установите APK.
2. Откройте TgWsProxy.
3. Проверьте порт. По умолчанию используется `1081`.
4. Для мобильной сети включите `CF first` или `CF only`.
5. Нажмите `Включить прокси`.
6. Нажмите `Применить в Telegram`.
7. Для диагностики временно включите runtime-логи и сохраните отчёт в выбранную папку.

Если используется ByeDPI или другой локальный прокси, порт `1081` обычно удобнее `1080`, чтобы избежать конфликта.

## Сборка

Требования:

- JDK 17
- Android SDK
- Gradle wrapper из репозитория
- Go toolchain для сборки native runtime

Debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Готовый debug APK Gradle кладёт в:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Локальный helper-скрипт дополнительно копирует APK в `artifacts/apk/debug/`:

```powershell
.\scripts\build-apk.ps1 -Configuration Debug
```

Release-подпись хранится только локально. Keystore не должен попадать в git; credentials передаются через переменные окружения:

```powershell
$env:KEYSTORE_PASSWORD="..."
$env:KEY_PASSWORD="..."
$env:KEY_ALIAS="tgwsproxy"
.\scripts\build-apk.ps1 -Configuration Release
```

Файлы APK, runtime-логи, `.so` и локальные Android build artifacts не должны попадать в git. Это зафиксировано в `.gitignore`.

## Диагностика

Полезные признаки в runtime-логах:

- `cfproxy hostname dial start` - попытка подключиться через системный hostname path.
- `cfproxy connected host=... via=hostname` - Cloudflare WebSocket успешно поднят.
- `bridge first-exit primary ...` - первичная причина закрытия bridge-сессии.
- `bridge secondary ...` - вторичная ошибка после закрытия одной из сторон.
- `stats: ... cf=... up=... down=...` - счётчики активных CF-сессий и трафика.

Если `cfproxy connected` есть, но `down=0.0B`, смотрите `bridge first-exit primary`: чаще всего это означает, что клиент Telegram закрыл локальный SOCKS-сокет до начала полезного обмена.

## Текущий статус

Подтверждено:

- CF hostname-first порядок подключения работает.
- `CF first` теперь реально выполняется до direct WS path.
- В CF-first/CF-only режиме WS pool warmup отключён, чтобы не шуметь direct-попытками.
- `DC1` больше не подменяется на `kws2.*` в direct WS domain mapping.
- Bridge-логирование фиксирует первичную причину закрытия сессии.
- Android routing намеренно отличается от Flowseal desktop: Android сохраняет `Worker first`, `CF first`, `Worker only`, `CF only`, `Auto` и `Direct + fallback routes`.
- `v1.3.2` добавляет built-in CF pool, per-domain health/cooldown, диагностику CF-доменов и ручной сброс cooldown.
- `Fake TLS` и автообновление CF-доменов из GitHub не входят в v1.3.2. Автообновление списка остаётся задачей для `v1.4.0`.

Не считается финально закрытым:

- стабильность на всех мобильных операторах;
- долгосрочная надёжность общего домена `pclead.co.uk`;
- workflow для настройки собственного Cloudflare-домена в один клик.

## Документация по расследованию

- [DEBUG_STATE.md](DEBUG_STATE.md) - краткое текущее состояние.
- [NEXT_STEPS.md](NEXT_STEPS.md) - ближайший roadmap.
- [ORIGINAL_VS_ANDROID_DIFF.md](ORIGINAL_VS_ANDROID_DIFF.md) - заметки по сравнению с Flowseal runtime и исходным Android-форком.

## Лицензия

См. [LICENSE](LICENSE).
