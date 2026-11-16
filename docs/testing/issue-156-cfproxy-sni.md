# Issue #156 — Cloudflare Proxy TLS SNI / WebSocket Host

## Изменение

Для Cloudflare Proxy TCP-соединение по-прежнему устанавливается к upstream-имени DC или к разрешённому IP. TLS ClientHello использует базовый домен зоны, а HTTP WebSocket upgrade сохраняет DC-specific Host и исходный путь.

| Уровень | DC2 | DC4 |
| --- | --- | --- |
| Upstream / HTTP Host | kws2.<base-domain> | kws4.<base-domain> |
| TLS SNI | <base-domain> | <base-domain> |
| WebSocket path | /apiws | /apiws |

Контракт применяется одинаково к MTProto и SOCKS5 Cloudflare Proxy. Встроенная диагностика использует upstream Host для DNS/TCP, базовый домен для TLS и исходный Host для HTTP HEAD или WebSocket upgrade.

Изменение ограничено Cloudflare Proxy. Direct WS и Cloudflare Worker сохраняют прежние SNI/Host. Если явно не задан отдельный TLS server name, WebSocket dialer использует исходный domain — прежнее поведение остальных маршрутов. Проверка сертификата не отключается; trust-all и отключение hostname verification не добавлялись.

Это исправление устраняет наблюдавшиеся TLS timeout в проверенной конфигурации. Оно не доказывает, что причиной всех проблем Telegram на всех сетях является фильтрация SNI, и не гарантирует доступность на каждом операторе.

## Источник и адаптация

Техническая идея адаптирована из форка [malordin/tg-ws-proxy-android](https://github.com/malordin/tg-ws-proxy-android), commit [a98fd27c384452b8b0e78a4273abf005077aad6b](https://github.com/malordin/tg-ws-proxy-android/commit/a98fd27c384452b8b0e78a4273abf005077aad6b).

В исходном отчёте форка описан следующий симптом: TCP к Cloudflare устанавливался, но TLS ClientHello с SNI вида kwsN.<domain> завершался таймаутом; с базовым доменом в SNI проходил WebSocket upgrade. В адаптации сохранены независимые значения TLS server name и HTTP Host. Изменения перенесены точечно в существующие MTProto, SOCKS5 и RouteProbe пути; форк целиком не копировался.

Источник диагностики: [docs/fork-sni.md](https://github.com/malordin/tg-ws-proxy-android/blob/main/docs/fork-sni.md).

## Автоматические проверки

Регрессионные тесты покрывают:

- сохранение базового TLS SNI и исходного HTTP Host для DC2/DC4;
- сохранение прежнего поведения Direct WS и Worker;
- WebSocket path /apiws;
- MTProto и SOCKS5 Cloudflare Proxy;
- IP fallback, отмену TLS handshake и ограниченные повторные попытки;
- HTTP-ошибки, включая 403/421/1013, без автоматического возврата к прежнему SNI;
- Kotlin RouteProbe: раздельные TLS SNI и HTTP Host;
- PowerShell SelfTest классификатора device smoke.

Итоговый статус автоматических проверок определяется CI для актуального HEAD PR #157. Результаты более раннего commit не заменяют проверку финального HEAD.

## Android device acceptance

**16 ноября 2026 года — USER_REPORTED_PASS.** Владелец сообщил, что Cloudflare Proxy работает после установки и запуска проверяемой версии на Android-устройстве.

Ограничения записи результата:

- JSON-отчёт и обезличенные агрегированные метрики в репозиторий не приложены;
- не зафиксированы точные значения DC2/DC4, число сессий и счётчики up/down;
- в сообщении не перечислены отдельно проверенные действия с сообщениями и медиа;
- оператор и тип сети не указаны.

Поэтому подтверждена работоспособность на проверенном пользователем устройстве и в его тестовой среде, но отдельная проверка доставки сообщений/медиа и матрица операторов остаются без независимого evidence.

Скрипт scripts/issue-156-cfproxy-device-smoke.ps1 формирует агрегированный статус CF_DUPLEX_OBSERVED_MEDIA_UNVERIFIED, если найдены двусторонние байты при selected_backend=actual_backend=cf_proxy_ws и fallback_used=false. Такой статус подтверждает двусторонний обмен байтами, но не подтверждает доставку пользовательских сообщений или медиа.

## Изоляция тестовой инфраструктуры

CfProxySmokeConfigReceiver находится в debug source set и защищён разрешением android.permission.DUMP. Он временно применяет CF-only policy, сохраняет исходные настройки и восстанавливает их после теста. В release source set этот receiver не включается.

Скрипт не удаляет приложение и пользовательские данные. Сырые временные логи удаляются после формирования локального агрегированного отчёта. Не публиковать device serial, IP, MTProto secret, Worker URL, ключи подписи или пользовательские данные.

## Состояние сетевой матрицы

| Среда | Статус |
| --- | --- |
| Android, тестовая среда владельца | USER_REPORTED_PASS |
| Wi-Fi, отдельно идентифицированная сеть | NOT_TESTED |
| МТС (Москва) | NOT_TESTED |
| МегаФон | NOT_TESTED |
| T2 | NOT_TESTED |
| Билайн | NOT_TESTED |
| DC2 и DC4 с отдельными счётчиками | NOT_RECORDED |
| Доставка сообщений и медиа с отдельным evidence | NOT_VERIFIED |

HTTP 101 и успешный TLS handshake сами по себе не являются доказательством работающего MTProto. Исходную жалобу #154 не закрывать на основании одной проверки на одном устройстве.

## Запуск автоматизированного Android smoke

Из корня репозитория:

~~~powershell
& ./scripts/issue-156-cfproxy-device-smoke.ps1 -InstallSignedDebug -SigningRoot 'C:/Base/projects/TGWSProxyAndroid' -Seconds 90
~~~

Скрипт использует существующий issue-135-rebuild-device.ps1, оригинальный ключ подписи и безопасную установку поверх приложения. Требуются одно авторизованное ARM64 Android-устройство, установленный Telegram и выбранный MTProto frontend. Полный результат и ограничения кодируются в локальном report.json; перед публикацией отчёт необходимо проверить на отсутствие чувствительных данных.
