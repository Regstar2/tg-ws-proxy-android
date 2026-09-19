# Развёртывание MTProto Worker для v1.10.14+

## Важно при обновлении с предыдущих версий

Начиная с **v1.10.14**, MTProto Worker использует новую архитектуру HTTPS chunk relay + Durable Objects.

Для работы Worker-маршрута недостаточно обновить только Android-приложение: существующие Cloudflare Worker, развёрнутые со старым кодом долгоживущего WebSocket-транспорта, необходимо обновить на актуальный код из этого репозитория.

Используйте:

- `scripts/cloudflare-worker/warp-bootstrap-worker.js` — актуальный Worker entry point; он добавляет ограниченный WARP provisioning bootstrap и передаёт весь остальной трафик в chunk-relay Worker;
- `scripts/cloudflare-worker/chunk-relay-status-worker.js` — существующая реализация MTProto chunk relay;
- `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc` — конфигурацию Worker/Durable Object для chunk relay.

Не используйте старую Worker-конфигурацию как замену новой схеме chunk relay для MTProto Worker v1.10.14.

## Архитектура

В v1.10.14 Android-клиент больше не держит один долгоживущий WebSocket как основной канал передачи MTProto-трафика через Worker.

Схема MTProto выглядит так:

```text
Telegram Android / TgWsProxy
        |
        | короткие HTTPS-запросы
        | upload chunks + ACK / downstream long polling
        v
warp-bootstrap-worker.js
        |
        | остальные маршруты без изменения
        v
chunk-relay-status-worker.js
        |
        | CHUNK_RELAY
        v
Shared Durable Object: relay-hub-v1
        |
        | постоянные независимые TCP-соединения
        v
Telegram DC
```

Клиент разбивает upload-поток на блоки и отправляет их через ограниченное окно параллельных HTTPS-запросов. Для v1.10.14 проверенный профиль — **12 KiB / window=3**.

Получение данных работает через отдельный adaptive long polling. Downstream-данные могут объединяться до 12 KiB, чтобы уменьшить количество HTTP-запросов.

### Shared RelayHub

Один Worker deployment использует общий именованный Durable Object hub `relay-hub-v1`, а не отдельный Durable Object на каждую MTProto-сессию.

Внутри hub каждая сессия по-прежнему полностью изолирована и имеет собственные:

- TCP-соединение с Telegram;
- sequence/ACK состояние;
- upload reorder/window state;
- downstream queue;
- lifecycle state;
- счётчики переданных данных.

Зависание или закрытие одной MTProto-сессии не должно блокировать остальные сессии в том же hub.

Текущее binding-имя — `CHUNK_RELAY`, класс Durable Object — `ChunkRelaySession`. Имя класса сохранено для совместимости, хотя фактически реализация работает как shared relay hub.

Подробности внутренней архитектуры: [`docs/architecture/chunk-relay-hub.md`](architecture/chunk-relay-hub.md).

## WARP provisioning bootstrap

`warp-bootstrap-worker.js` добавляет узкий HTTPS fallback для Consumer WARP provisioning. Он нужен для сетей, где прямой TLS к `api.cloudflareclient.com` не завершается, хотя обычные Cloudflare Worker-домены доступны.

Bootstrap не является универсальным HTTP/TCP proxy. Разрешены только следующие маршруты:

```text
GET   /warp-bootstrap/health
POST  /warp-bootstrap/v0a4005/reg
PATCH /warp-bootstrap/v0a4005/reg/<registration-id>
```

Worker всегда обращается только к фиксированному upstream `https://api.cloudflareclient.com`. Registration request пересобирается только из `key`, activation request — только из `warp_enabled=true`; произвольный URL, host, query и пользовательские заголовки не проксируются. Размер request/response ограничен, ответы помечаются `Cache-Control: no-store`, чувствительные тела запросов и ответов не логируются.

Curve25519/WireGuard private key должен генерироваться и храниться на Android-устройстве. Через Worker передаётся public key и, на этапе activation, registration bearer token, поэтому bootstrap следует рассматривать как доверенный компонент конкретного deployment.

Идентификатор bootstrap-контракта:

```text
X-Tgws-Warp-Bootstrap-Revision: warp-bootstrap-v1
```

Health response:

```json
{"service":"warp-bootstrap","revision":"warp-bootstrap-v1"}
```

Android-клиент считает endpoint совместимым только после успешного HTTPS health-check с ожидаемыми `service` и `revision`. Redirect на другой host не используется. Provisioning endpoint-ы хранятся отдельно от обычного Telegram Worker Pool: добавление Worker для создания WARP-профиля не делает его маршрутом `cf_worker_ws`.

## Развёртывание одного Worker

Требуется Cloudflare Workers с поддержкой Durable Objects и установленный Wrangler.

Из корня репозитория:

```powershell
npx wrangler@latest login
npx wrangler@latest deploy --config scripts/cloudflare-worker/wrangler.chunk-relay.jsonc
```

Перед публикацией проверьте `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc`:

```jsonc
{
  "name": "tgproxy",
  "main": "warp-bootstrap-worker.js",
  "durable_objects": {
    "bindings": [
      {
        "name": "CHUNK_RELAY",
        "class_name": "ChunkRelaySession"
      }
    ]
  }
}
```

После deploy используйте выданный Cloudflare Worker-домен либо в отдельном списке **WARP / AmneziaWG → Пользовательские bootstrap Worker**, либо, для инфраструктуры проекта, в централизованном built-in provisioning pool. Не добавляйте provisioning-only deployment в обычный Telegram Worker Pool, если он не должен принимать Telegram proxy traffic.

Проверьте, что новый wrapper действительно опубликован:

```powershell
$Worker = "https://tgproxy.<account>.workers.dev"
Invoke-RestMethod "$Worker/warp-bootstrap/health"
```

Ожидаемый ответ:

```json
{"service":"warp-bootstrap","revision":"warp-bootstrap-v1"}
```

Этот health-check подтверждает только публикацию bootstrap wrapper. Реальный `fetch()` к Consumer WARP API должен быть отдельно подтверждён provisioning smoke-test; unit-тесты не заменяют сетевую проверку Cloudflare deployment.

## Рекомендуется несколько Worker

Для provisioning рекомендуется развернуть **не один, а несколько Worker deployment**. Пользовательские deployment добавляются в отдельный provisioning-список приложения; встроенные deployment задаются централизованно в приложении и управляются одним toggle.

Практический стартовый вариант — **2–3 Worker**.

Например:

```text
tgproxy-primary.<account>.workers.dev
tgproxy-secondary.<account>.workers.dev
tgproxy-backup.<account>.workers.dev
```

Wrangler поддерживает переопределение имени через `--name`, поэтому один и тот же config можно развернуть несколько раз:

```powershell
npx wrangler@latest deploy --config scripts/cloudflare-worker/wrangler.chunk-relay.jsonc --name tgproxy-primary
npx wrangler@latest deploy --config scripts/cloudflare-worker/wrangler.chunk-relay.jsonc --name tgproxy-secondary
npx wrangler@latest deploy --config scripts/cloudflare-worker/wrangler.chunk-relay.jsonc --name tgproxy-backup
```

Все Worker должны использовать актуальный код v1.10.14+ и новую chunk-relay/Durable Object конфигурацию.

После развёртывания добавьте все домены в Worker-пул TgWsProxy и используйте стратегию распределения, поддерживающую несколько Worker.

### Зачем несколько Worker

Несколько Worker дают:

- резерв при недоступности одного домена;
- распределение независимых MTProto-сессий;
- возможность пропустить Worker, который временно упёрся в Durable Objects quota;
- меньшее влияние локальных ошибок конкретного Worker deployment;
- более устойчивое восстановление новых сессий.

При `ROUND_ROBIN` выбор остаётся sticky в пределах одной MTProto-сессии: существующая сессия не прыгает между Worker, а новые независимые сессии могут распределяться по пулу.

Если Worker сообщает об исчерпании Durable Objects quota, клиент использует per-domain circuit breaker и временно не выбирает этот домен для новых сессий до reset.

Несколько Worker не объединяют одну MTProto-сессию в один общий поток между разными доменами. Они дают резервирование и распределение **между независимыми сессиями**.

## Проверка после deploy

После обновления Worker рекомендуется проверить:

1. `GET /warp-bootstrap/health` и revision `warp-bootstrap-v1`;
2. подключение Telegram через Worker-маршрут;
3. отправку и получение сообщений;
4. загрузку и скачивание медиа;
5. reconnect после разрыва соединения;
6. создание нескольких MTProto-сессий;
7. работу пула из нескольких Worker;
8. отсутствие возврата на старый WebSocket Worker transport;
9. отдельный Consumer WARP provisioning smoke-test через bootstrap после подключения Android fallback.

В диагностике v1.10.14 должны быть видны признаки chunk-relay/RelayHub пути, а Worker должен отвечать актуальными revision headers.

## Ограничения

Новая схема приоритетно решает проблему стабильности долгоживущего Worker-соединения. Worker-транспорт остаётся медленнее прямого подключения и зависит от ограничений Cloudflare Workers/Durable Objects.

WARP bootstrap использует Worker-side `fetch()` к `api.cloudflareclient.com`: это поддерживаемый механизм исходящих HTTP(S)-запросов Workers, но фактическая доступность Consumer WARP registration API из конкретного deployment должна быть подтверждена реальным smoke-test.

Поэтому Worker остаётся дополнительным маршрутом обхода, а использование нескольких Worker повышает отказоустойчивость, но не отменяет квоты Cloudflare и не гарантирует скорость прямого соединения.
