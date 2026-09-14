# Развёртывание MTProto Worker для v1.10.14+

## Важно при обновлении с предыдущих версий

Начиная с **v1.10.14**, MTProto Worker использует новую архитектуру HTTPS chunk relay + Durable Objects.

Для работы Worker-маршрута недостаточно обновить только Android-приложение: существующие Cloudflare Worker, развёрнутые со старым кодом долгоживущего WebSocket-транспорта, необходимо обновить на актуальный код из этого репозитория.

Используйте:

- `scripts/cloudflare-worker/chunk-relay-status-worker.js` — актуальный Worker entry point;
- `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc` — конфигурацию Worker/Durable Object для chunk relay.

Не используйте старую Worker-конфигурацию как замену новой схеме chunk relay для MTProto Worker v1.10.14.

## Архитектура

В v1.10.14 Android-клиент больше не держит один долгоживущий WebSocket как основной канал передачи MTProto-трафика через Worker.

Схема выглядит так:

```text
Telegram Android / TgWsProxy
        |
        | короткие HTTPS-запросы
        | upload chunks + ACK / downstream long polling
        v
Cloudflare Worker
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
  "main": "chunk-relay-status-worker.js",
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

После deploy используйте выданный Cloudflare Worker-домен в настройках Worker-пула TgWsProxy.

## Рекомендуется несколько Worker

Для постоянного использования рекомендуется развернуть **не один, а несколько Worker deployment** и добавить их домены в Worker-пул приложения.

Практический стартовый вариант — **2–3 Worker**.

Например:

```text
tgproxy-primary.<account>.workers.dev
tgproxy-secondary.<account>.workers.dev
tgproxy-backup.<account>.workers.dev
```

Все Worker должны использовать актуальный код v1.10.14+ и новую chunk-relay/Durable Object конфигурацию.

Для отдельных deployment задайте разные Worker names. Это можно сделать отдельными копиями `wrangler.chunk-relay.jsonc` с разным полем `name` либо эквивалентной настройкой Wrangler.

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

1. подключение Telegram через Worker-маршрут;
2. отправку и получение сообщений;
3. загрузку и скачивание медиа;
4. reconnect после разрыва соединения;
5. создание нескольких MTProto-сессий;
6. работу пула из нескольких Worker;
7. отсутствие возврата на старый WebSocket Worker transport.

В диагностике v1.10.14 должны быть видны признаки chunk-relay/RelayHub пути, а Worker должен отвечать актуальными revision headers.

## Ограничения

Новая схема приоритетно решает проблему стабильности долгоживущего Worker-соединения. Worker-транспорт остаётся медленнее прямого подключения и зависит от ограничений Cloudflare Workers/Durable Objects.

Поэтому Worker остаётся дополнительным маршрутом обхода, а использование нескольких Worker повышает отказоустойчивость, но не отменяет квоты Cloudflare и не гарантирует скорость прямого соединения.
