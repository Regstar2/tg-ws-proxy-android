# Cloudflare Worker: Telegram relay и WARP provisioning

Этот документ описывает Telegram Worker и отдельный WARP/AWG provisioning Worker. С поддержкой #101 Telegram Worker может использовать один из двух транспортов, выбираемых явно.

## Какой Worker нужен

### Telegram Worker (`cf_worker_ws`)

Этот Worker передаёт **Telegram MTProto traffic**. По умолчанию используется HTTPS Chunk Relay + Durable Objects (проверенный baseline v1.10.14+). Начиная с поддержки #101 доступен также оригинальный Flowseal Legacy WebSocket `/apiws`. Его домен добавляется в одиночный Worker или Worker Pool приложения и может участвовать в route policy.

Для **Chunk Relay** используйте `scripts/cloudflare-worker/warp-bootstrap-worker.js` вместе с `scripts/cloudflare-worker/chunk-relay-status-worker.js` и `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc`.

Для **Legacy WebSocket** используйте оригинальную инструкцию [Flowseal CF Worker](https://github.com/Flowseal/tg-ws-proxy/blob/main/docs/RU/CfWorker.md). Этот Worker принимает WebSocket на `/apiws?dst=<telegram-ip>`, устанавливает TCP-соединение на Telegram `:443` и пересылает бинарные WebSocket messages в обе стороны. Durable Objects ему не нужны.

### Выбор транспорта в Android

- **Одиночный Worker, Worker Pool выключен:** Настройки → Cloudflare → Worker Pool → Legacy Worker → «Протокол Telegram Worker».
- **Worker Pool включён:** добавьте или измените Worker и явно выберите `HTTPS Chunk Relay` или `Flowseal Legacy WebSocket` в форме редактирования. Протокол закреплён за каждым Worker; стратегия выбора и failover могут выбирать Worker разных типов только между новыми сессиями.
- Сохранённые ранее Worker (включая мигрированные из одиночного режима) продолжают использовать **Chunk Relay**. Приложение не делает автоматическую смену протокола при ошибке.
- Для Legacy WebSocket проверка подтверждает только корректный TLS/WebSocket handshake (`101` + валидный `Sec-WebSocket-Accept`); для Chunk Relay — доступность relay/DO и совместимость API. **Ни одна из этих проверок не подтверждает full-duplex MTProto, работу медиа и устойчивость Android.**
- Legacy WebSocket **экспериментален** в Android: на прежних версиях наблюдались зависания крупных медиа-передач (#29). Используйте Worker-only и реальное устройство для проверки сообщений, загрузки и отправки больших файлов. Если не работает — верните Chunk Relay, не меняя тип автоматически.

 
**Проверка DC2 (#101, 14.10.2026).** На конкретном существующем Legacy Worker `black-mountain-bb46.vovasazuz.workers.dev` сравнили физические IPv4-цели при неизменном логическом DC2 и одинаковом протоколе. `149.154.167.51` дал **8/8** двусторонних сессий, включая две медийные (`up_bytes=47 423`, `down_bytes=187 804`, без ошибок моста). `149.154.167.220` дал **0/48** сессий с доставленными MTProto-ответами, в 25 случаях — отклонённый HTTP **400**, в остальных — нулевой downstream. Это результат данного Worker/сети/момента, а не глобальная проверка всех серверов Telegram. Успех доставки сообщений и больших медиа в пользовательском интерфейсе этим тестом не подтверждён.

- **Для Legacy Worker с таким симптомом сначала проверяйте стандартный DC2 `149.154.167.51`**. Он уже входит в конфигурацию приложения по умолчанию; замена всех DC2 на `.220` не является исправлением. Не включайте экспериментальное перенаправление медийного DC2 на `.220` как универсальную рекомендацию.
- Ошибка `worker_non_mtproto_plaintext_reply ... http_status=400` означает, что внутри принятого WebSocket-соединения пришёл обычный HTTP-ответ, а не MTProto. Такой ответ отклоняется до передачи Telegram. По одному коду 400 нельзя установить, кто именно сформировал ответ.
- Явные пользовательские назначения IP сохраняются: приложение не должно незаметно переписывать их и не переключает тип Worker автоматически. Для сравнения адресов используйте debug A/B `-LegacyOnly -LegacyDC2AB`; он вернёт исходные настройки после проверки.


**Device acceptance (#101):** запустите `scripts/worker-issue-101-device-smoke.ps1 -InstallSignedDebug -ChunkRelayWorkerDomain <свой-chunk.workers.dev> -LegacyWorkerDomain <свой-flowseal.workers.dev>` в PowerShell с одним подключённым Android-устройством. Подписанный **debug APK** использует тот же локальный release-ключ из `release-signing.env`; receiver доступен только в debug-сборке и не включается в релиз. Скрипт без запросов Enter переключает домен/протокол, включает Worker-only, открывает приложение, **запускает и останавливает ProxyService**, запускает Telegram (если пакет обнаружен), ожидает по `-PhaseSeconds` секунд (по умолчанию 90) для каждого транспорта, собирает лог и автоматический отчёт, а затем восстанавливает исходные настройки. Можно запустить только проблемный Legacy через `-LegacyOnly`. Во время окна теста нужно самостоятельно выполнить действия в Telegram (отправка/получение сообщений и крупных файлов); скрипт не отправляет реальные сообщения от имени пользователя. Отчёт отдельно показывает `observedNetworkStatus` и `manualTelegramTest=not_machine_verified`: ненулевые сетевые счётчики **не доказывают** исправную передачу медиа. Даже при ошибке скрипт пытается восстановить настройки и сохраняет частичные логи в ZIP. Не публикуйте личные домены и сырые логи в общедоступных issues.

Standalone provisioning-only Worker для WARP/AWG не поддерживает ни `/apiws`, ни `/chunk-relay`, и не является Telegram Worker.

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

Начиная с **v1.10.14**, MTProto Worker по умолчанию использует HTTPS Chunk Relay + Durable Objects. Legacy WebSocket добавлен обратно как явный режим совместимости в #101.

Для работы Worker-маршрута недостаточно обновить только Android-приложение: существующие Cloudflare Worker, развёрнутые со старым кодом долгоживущего WebSocket-транспорта, необходимо обновить на актуальный код из этого репозитория.

Используйте:

- `scripts/cloudflare-worker/warp-bootstrap-worker.js` — комбинированный Worker entry point для Wrangler: WARP provisioning bootstrap + передача остального трафика в chunk-relay Worker;
- `scripts/cloudflare-worker/warp-bootstrap-standalone-worker.js` — standalone provisioning-only Worker без imports, Durable Objects и bindings; его можно целиком вставить через Cloudflare Dashboard;
- `scripts/cloudflare-worker/chunk-relay-status-worker.js` — существующая реализация MTProto chunk relay;
- `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc` — конфигурацию Worker/Durable Object для chunk relay.

Оригинальный Flowseal Worker используйте только при явном выборе **Flowseal Legacy WebSocket**. Он не является заменой Chunk Relay с Durable Objects и не пройдёт проверку протокола Chunk Relay.

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

Для `cf_worker_ws` можно развернуть **несколько Worker deployment** и добавить их в Telegram Worker Pool. Каждый домен должен иметь собственный явно заданный тип протокола.

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

Все **Chunk Relay** Worker должны использовать актуальный Chunk Relay код v1.10.14+ и Durable Object конфигурацию. **Legacy WebSocket** Worker используют отдельный upstream-код Flowseal и не требуют DO.

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
7. соответствие реально используемого транспорта выбранному типу Worker, отсутствие неявного переключения протоколов.

Для **provisioning Worker** отдельно проверьте:

1. `GET /warp-bootstrap/health` и revision `warp-bootstrap-v1`;
2. успешную проверку endpoint в списке пользовательских bootstrap Worker;
3. реальное создание нового Consumer WARP-профиля через этот endpoint при недоступном direct API;
4. отсутствие Worker в обычном Telegram Worker Pool, если deployment provisioning-only.

### Проверка совместимости Telegram Worker в Android

Начиная с исправления #100, «Проверить Worker» и проверки Worker Pool **не используют** старый `/apiws` handshake. Диагностика проверяет текущий транспорт HTTPS Chunk Relay `chunk-relay-mtproto-v10` и hub `relay-hub-v1`.

Один запуск для **одного DC2** использует свежий случайный идентификатор сессии и не более четырёх HTTPS-запросов:

1. `POST /chunk-relay/open` — ожидается HTTP 204 и оба revision header; этот шаг реально открывает TCP-соединение Worker → Telegram DC2.
2. `POST /chunk-relay/up` с заведомо недопустимым `seq=0` и **без MTProto payload** — ожидается HTTP 400 с revision headers. Это проверка обработчика и валидации, а не передача данных.
3. `GET /chunk-relay/down?ack=0&wait=0` — ожидается HTTP 204/200 с revision headers. Нулевое ожидание исключает длительный poll.
4. `GET /chunk-relay/close` — завершает тестовую сессию и освобождает ресурсы. При сетевой ошибке после начала открытия также выполняется попытка закрытия.

Для запросов установлен лимит времени; запросы выполняются последовательно. При **выборе Chunk Relay** несовместимый Legacy WebSocket Worker (`/apiws`, без Chunk Relay revision headers) показывает несовместимый протокол, даже если его `/apiws` отвечает HTTP 101. При **выборе Legacy WebSocket** отдельно проверяется WebSocket upgrade/Accept, но не MTProto. HTTP 404/405, 409/410, 5xx, Durable Object quota и сетевые тайм-ауты не отмечаются успехом.

**Важно:** успешная проверка подтверждает совместимость версии, доступность Worker/DO и распознавание HTTP-операций, но не двусторонний обмен Telegram MTProto. Отдельно проверьте Telegram в режиме Worker-only: подключение, сообщения, загрузку и отправку медиа, а также `actual_backend=cf_worker_ws`, `fallback_used=false`, `down_bytes>0` в логах.

В диагностике v1.10.14 должны быть видны признаки chunk-relay/RelayHub пути, а Worker должен отвечать актуальными revision headers.

## Ограничения

Новая схема приоритетно решает проблему стабильности долгоживущего Worker-соединения. Worker-транспорт остаётся медленнее прямого подключения и зависит от ограничений Cloudflare Workers/Durable Objects.

WARP bootstrap использует Worker-side `fetch()` к `api.cloudflareclient.com`: это поддерживаемый механизм исходящих HTTP(S)-запросов Workers, но фактическая доступность Consumer WARP registration API из конкретного deployment должна быть подтверждена реальным smoke-test.

Поэтому Worker остаётся дополнительным маршрутом обхода, а использование нескольких Worker повышает отказоустойчивость, но не отменяет квоты Cloudflare и не гарантирует скорость прямого соединения.
