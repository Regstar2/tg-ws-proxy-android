# Режимы подключения (v1.4.0)

Документация к ветке `feature/cf-worker-v1.7`.

## Runtime (Go)

Маршруты:

- `direct_ws` — `wss://kws{dc}.web.telegram.org/apiws`
- `cf_worker_ws` — `wss://<worker>/apiws?dst=...&dc=...&media=...`
- `cf_proxy_ws` — `wss://kws{dc}.<cf-domain>/apiws`
- `tcp_fallback` — TCP к IP DC на порт 443

Порядок маршрутов:

| Режим | Порядок |
| --- | --- |
| `auto` | Direct WS → Worker → CF → TCP |
| `direct_with_fallback` | Direct WS → Worker → CF → TCP |
| `worker_first` | Worker → CF → Direct WS → TCP |
| `cf_first` | CF → Worker → Direct WS → TCP |
| `worker_only` | только Worker |
| `cf_only` | только CF |
| `direct_only` | только Direct WS |

`worker_only` не допускает direct TCP passthrough для Telegram-like traffic. `cf_only` не допускает direct TCP passthrough для Telegram-like traffic. Неизвестные Telegram-like IPv4/IPv6 destinations в этих режимах закрываются явно, а не обходят restricted mode через TCP fallback.

## v1.3.2 CF domain pool

Android fork keeps a manual CF domain for advanced users, but also includes a built-in CF domain pool. If the manual domain returns `429`, `403`, `5xx`, or repeatedly fails during timeout/TLS/WebSocket setup, it is temporarily placed into cooldown and the runtime tries another CF domain or the next route allowed by the selected mode.

Каждый домен получает health state: source, success/failure counters, last success/failure, last reason, cooldown, latency. Ручной домен выбирается первым, пока он healthy; после cooldown selector переходит к built-in pool и не повторяет один и тот же плохой домен в рамках одного подключения.

`429` означает, что Cloudflare ограничил endpoint по rate-limit. Один ручной домен не должен быть единственной опорой, поэтому built-in pool нужен даже при настроенном manual domain.

Фоновое обновление списка с GitHub — **не реализовано** в `v1.3.2`; это задача для `v1.4.0`. `Fake TLS` тоже не входит в `v1.3.2`.

Android routing intentionally differs from Flowseal desktop. Flowseal desktop v1.7.0 убрал отдельный CF priority, но Android сохраняет мобильные режимы: `Worker first`, `CF first`, `Worker only`, `CF only`, `Auto`, `Direct + fallback routes`.

`Fake TLS` не реализован в v1.3.2. GitHub CF domain auto-update не реализован в v1.3.2.

## v1.4.0 CF domain auto-update

The Android fork can update its Cloudflare proxy domain list from Flowseal upstream. The update is non-critical: if GitHub is unavailable, the app keeps using the last cached list. If no cached list exists, the built-in list remains available. Manual user domain always has priority unless it is temporarily placed into cooldown by health checks.

Источники CF-доменов:

1. `manual`
2. `cached_upstream`
3. `built_in`

Порядок выбора: `Manual -> Cached upstream -> Built-in`. Health/cooldown применяется ко всем источникам одинаково: плохой manual domain может уступить cached upstream, а исчерпанный cached upstream — встроенному списку.

В UI появились `Update CF domain list` и `Auto-update CF domains`. Автообновление ограничено одной попыткой в 24 часа и не блокирует запуск proxy. Пустой, битый или невалидный downloaded list не заменяет прежний cache.

`Fake TLS` и GitHub pinned TLS fallback намеренно не входят в `v1.4.x`.

## v1.5.0 adaptive Auto routing

Auto and **Direct + fallback routes** use adaptive scoring in the Android fork. See [ADAPTIVE_ROUTING.md](ADAPTIVE_ROUTING.md).

Manual modes (`Worker only`, `CF only`, `Direct only`, `Worker first`, `CF first`) are unchanged.

## v1.4.1 mirror / resilient upstream update

CF-domain list updates are best-effort and do not block proxy startup. Primary GitHub is tried first; an optional user HTTPS mirror is used on failure. Failed updates keep the previous cache; the built-in list remains available. See [CF_DOMAIN_POOL.md](CF_DOMAIN_POOL.md) for details.

## Android UI

Настройки передают в Go строку вида:

```text
2:149.154.167.220,@connection_mode=worker_first,@worker_enabled=1,@worker_domain=example.username.workers.dev,@cfproxy=1,...
```

Старые флаги `@cfproxy_priority` / `@cfproxy_only` сохранены для совместимости.

## Проверка

```bash
go test ./tgwsroute/...
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

См. также [cloudflare-worker.md](./cloudflare-worker.md).
