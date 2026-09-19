# Cloudflare Worker: Telegram relay и WARP provisioning

Этот документ — актуальный гайд для `v1.11.0`. В приложении есть **два разных типа Worker**, и их нельзя смешивать.

## Какой Worker нужен

### Telegram Worker (`cf_worker_ws`)

Этот Worker передаёт **Telegram MTProto traffic**. Для `v1.10.14+` используется HTTPS chunk relay + Durable Objects. Его домен добавляется в обычный Worker Pool приложения и может участвовать в route policy.

Для такого deployment используйте `scripts/cloudflare-worker/warp-bootstrap-worker.js` вместе с `scripts/cloudflare-worker/chunk-relay-status-worker.js` и `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc`.

### Provisioning Worker для WARP/AWG

Этот Worker нужен **только для создания и активации Consumer WARP-профиля**, когда прямой доступ к `api.cloudflareclient.com` недоступен. Он не принимает Telegram traffic и не должен добавляться в обычный `cf_worker_ws` Worker Pool.

Для отдельного provisioning-only deployment используйте `scripts/cloudflare-worker/warp-bootstrap-standalone-worker.js`. Он не требует Durable Objects, bindings, Variables или Secrets.

## Новый путь автоматического создания WARP/AWG-профиля

При **Настройки → Cloudflare → WARP / AmneziaWG → Создать профиль** приложение использует следующий bounded порядок:

```text
новая локальная WireGuard keypair
        ↓
1. выбранный WORKING AWG/WARP-профиль как bootstrap transport
        ↓ fail / unavailable
2. другие WORKING AWG/WARP-профили
        ↓ fail / unavailable
3. direct HTTPS → api.cloudflareclient.com
        ↓ fail
4. включённые пользовательские provisioning Workers
        ↓ all fail / none
5. 3 встроенных provisioning Workers проекта, только если toggle включён
        ↓
Consumer WARP registration + activation
        ↓
AWG config + bounded autotune
        ↓
реальный Telegram MTProto req_pq_multi → resPQ
        ↓
повторное full-duplex подтверждение (2/2)
        ↓
сохранение нового профиля
```

Каждый автоматически создаваемый профиль получает **новую локальную keypair и отдельную Consumer WARP registration**. Сохранённая registration другого профиля не клонируется.

### Три встроенных Worker проекта

В `v1.11.0` приложение содержит три project-provided provisioning endpoint. Они:

- используются только как последний Worker fallback при генерации/активации WARP-профиля;
- не добавляются в Telegram Worker Pool и никогда не передают Telegram traffic;
- управляются одним переключателем **Использовать встроенные bootstrap Worker**;
- включены по умолчанию для cold-start provisioning, но могут быть полностью отключены пользователем;
- используют тот же ограниченный протокол `warp-bootstrap-v1`, что и custom provisioning Workers.

Если встроенный pool выключен, приложение не обращается к этим endpoint даже при отказе direct/custom путей.

### Пользовательский provisioning Worker

В **WARP / AmneziaWG** можно добавить свой HTTPS endpoint. Custom Workers проверяются раньше встроенного pool. URL должен быть базовым HTTPS URL без credentials, query/fragment и произвольного path.

Совместимость проверяется запросом:

```text
GET /warp-bootstrap/health
```

Ожидаемый ответ:

```json
{"service":"warp-bootstrap","revision":"warp-bootstrap-v1"}
```

Provisioning protocol ограничен только следующими операциями:

```text
GET   /warp-bootstrap/health
POST  /warp-bootstrap/v0a4005/reg
PATCH /warp-bootstrap/v0a4005/reg/<registration-id>
```

Worker обращается только к фиксированному upstream `https://api.cloudflareclient.com` и не является универсальным HTTP/TCP proxy.

---

## Важно при обновлении с предыдущих версий

Начиная с **v1.10.14**, MTProto Worker использует новую архитектуру HTTPS chunk relay + Durable Objects.

Для работы Worker-маршрута недостаточно обновить только Android-приложение: существующие Cloudflare Worker, развёрнутые со старым кодом долгоживущего WebSocket-транспорта, необходимо обновить на актуальный код из этого репозитория.

Используйте:

- `scripts/cloudflare-worker/warp-bootstrap-worker.js` — комбинированный Worker entry point для Wrangler: WARP provisioning bootstrap + передача остального трафика в chunk-relay Worker;
- `scripts/cloudflare-worker/warp-bootstrap-standalone-worker.js` — standalone provisioning-only Worker без imports, Durable Objects и bindings; его можно целиком вставить через Cloudflare Dashboard;
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

## Standalone bootstrap через Cloudflare Dashboard

Для встроенных и пользовательских provisioning-only endpoint используйте `scripts/cloudflare-worker/warp-bootstrap-standalone-worker.js`.

Этот файл:

- не импортирует другие файлы;
- не требует `CHUNK_RELAY`;
- не использует Durable Objects;
- не принимает Telegram proxy traffic;
- обслуживает только `/warp-bootstrap/*`, а остальные пути возвращают `404`;
- использует тот же `warp-bootstrap-v1` контракт и те же ограничения запросов, что и комбинированный entry point.

Развёртывание через GUI:

1. Cloudflare Dashboard → **Workers & Pages** → **Create** → Worker.
2. Откройте **Edit code**.
3. Замените пример кода полным содержимым `warp-bootstrap-standalone-worker.js`.
4. Нажмите **Deploy**.
5. Проверьте:
   ```powershell
   $Worker = "https://<name>.<account>.workers.dev"
   Invoke-RestMethod "$Worker/warp-bootstrap/health"
   ```

Ожидаемый ответ:

```json
{"service":"warp-bootstrap","revision":"warp-bootstrap-v1"}
```

Никакие Variables, Secrets, Durable Object bindings или Routes для базового provisioning-only deployment не требуются.

После проверки endpoint:

1. откройте **Настройки → Cloudflare → WARP / AmneziaWG**;
2. откройте список **Пользовательские bootstrap Worker**;
3. добавьте базовый HTTPS URL Worker без `/warp-bootstrap/...`;
4. выполните проверку Worker в приложении;
5. оставьте endpoint включённым и запустите **Создать профиль**.

Не добавляйте такой standalone Worker в обычный Telegram Worker Pool: он намеренно возвращает `404` для Telegram relay paths.

## Развёртывание Telegram Worker (chunk relay)

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

После deploy добавьте выданный Cloudflare Worker-домен в обычный **Telegram Worker Pool** TgWsProxy и используйте его как маршрут `cf_worker_ws`.

Комбинированный entry point также отвечает на `/warp-bootstrap/*`, поэтому тот же deployment при необходимости можно отдельно добавить в список **WARP / AmneziaWG → Пользовательские bootstrap Worker**. Эти две записи остаются независимыми: наличие домена в одном списке не добавляет его в другой автоматически.

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

## Несколько Telegram Worker

Для `cf_worker_ws` можно развернуть **несколько одинаковых chunk-relay Worker deployment** и добавить их в обычный Telegram Worker Pool.

Практический стартовый вариант — **2–3 Telegram Worker**. Это отдельная рекомендация от встроенного provisioning pool: три встроенных project Worker используются только при создании WARP-профиля и не являются Telegram Worker.

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

Все Telegram Worker должны использовать актуальный код v1.10.14+ и chunk-relay/Durable Object конфигурацию.

После развёртывания добавьте их домены в обычный Telegram Worker Pool TgWsProxy и используйте поддерживаемую стратегию распределения.

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

Для **Telegram Worker** проверьте:

1. подключение Telegram через `cf_worker_ws`;
2. отправку и получение сообщений;
3. загрузку и скачивание медиа;
4. reconnect после разрыва соединения;
5. создание нескольких MTProto-сессий;
6. работу пула из нескольких Telegram Worker;
7. отсутствие возврата на старый WebSocket Worker transport.

Для **provisioning Worker** отдельно проверьте:

1. `GET /warp-bootstrap/health` и revision `warp-bootstrap-v1`;
2. успешную проверку endpoint в списке пользовательских bootstrap Worker;
3. реальное создание нового Consumer WARP-профиля через этот endpoint при недоступном direct API;
4. отсутствие Worker в обычном Telegram Worker Pool, если deployment provisioning-only.

В диагностике v1.10.14 должны быть видны признаки chunk-relay/RelayHub пути, а Worker должен отвечать актуальными revision headers.

## Ограничения

Новая схема приоритетно решает проблему стабильности долгоживущего Worker-соединения. Worker-транспорт остаётся медленнее прямого подключения и зависит от ограничений Cloudflare Workers/Durable Objects.

WARP bootstrap использует Worker-side `fetch()` к `api.cloudflareclient.com`: это поддерживаемый механизм исходящих HTTP(S)-запросов Workers, но фактическая доступность Consumer WARP registration API из конкретного deployment должна быть подтверждена реальным smoke-test.

Поэтому Worker остаётся дополнительным маршрутом обхода, а использование нескольких Worker повышает отказоустойчивость, но не отменяет квоты Cloudflare и не гарантирует скорость прямого соединения.
