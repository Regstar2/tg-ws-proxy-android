# Issue #101 — изоляция неисправности на стороне Cloudflare Worker

## Что установлено

Экспорт Workers Logs за 2026-10-12 содержит 44 отдельных запроса
\`/apiws?dc=2&dst=149.154.167.51\` и 15 запросов \`/apiws?dst=www.google.com\`.
Для Telegram DC2 38 завершились со статусом \`canceled\` и шесть —
\`exception\` / \`Network connection lost.\`.
Для диагностических запросов к Google встречаются \`loadShed\` и ошибки
\`internal error; reference = ...\`.

Это доказывает, что HTTP/WebSocket-запросы доходили до Worker, но **не**
доказывает доставку последующих WebSocket payload до обработчика \`message\`
или успешное TCP-соединение Worker с целевым сервером.

\`canceled\` часто означает отключение клиента от длительного WebSocket.
Не считать его самостоятельным доказательством повреждения TCP-передачи.
\`loadShed\` требует отдельного изучения метрик/ресурсов Cloudflare.

## Безопасное развёртывание

1. В Cloudflare **Workers & Pages** создайте **новый** Worker, например
   \`flowseal-issue101-diagnostic\`. Не заменяйте рабочий
   \`black-mountain-bb46\` или Chunk Relay.
2. Вставьте код из [диагностического Worker](../scripts/worker-issue-101-cloudflare-diagnostic.mjs)
   в редактор нового Worker и выполните **Deploy**.
3. Убедитесь, что для нового Worker включена вкладка **Observability → Logs**.
4. В PowerShell запустите:

   \`\`\`powershell
   Set-Location "C:\Base\projects\TGWSProxyAndroid"
   & .\scripts\worker-issue-101-ip-ab.ps1 `
       -WorkerDomain "flowseal-issue101-diagnostic.<your-subdomain>.workers.dev" `
       -HelloProfiles compact `
       -Rounds 1 `
       -TimeoutSeconds 15
   \`\`\`

   **Замените имя на фактически выданный Cloudflare домен.**
   APK не изменяется, root не нужен, настройки Telegram не затрагиваются.
5. Экспортируйте результат по пути \`REPORT TO SHARE\` и серверные записи
   с \`component=flowseal_issue101\` из Workers Logs за интервал пробы.

## Интерпретация стадий

| Поле \`phase\` | Что оно подтверждает |
| --- | --- |
| \`ws_accepted\` | Worker принял WebSocket Upgrade |
| \`tcp_opened\` | \`socket.opened\` завершился успешно |
| \`ws_message_received\` | Бинарное сообщение доставлено JS-обработчику |
| \`tcp_write_completed\` | Запись в \`socket.writable\` завершилась |
| \`tcp_read_completed\` | Worker прочитал байты из upstream TCP |
| \`ws_send_completed\` | Worker отправил байты обратно в WebSocket |
| \`tcp_read_or_open_failed\`, \`tcp_write_failed\` | Конкретная стадия ошибки |
| \`closing\` | Итоговые счётчики отправленных и полученных байтов |

Логи не содержат бинарный payload, MTProto-секреты, идентификатор Telegram,
IP-адрес клиента или содержимое сообщений. Данные Cloudflare
инфраструктурных trace-событий могут иметь дополнительную сетевую метаинформацию.

Диагностический Worker **намеренно ограничен** двумя целями:
\`www.google.com:443\` и \`149.154.167.51:443\`. Не используйте его
как универсальный открытый прокси или в производственном приложении.

## Проверка реализации до развёртывания

\`\`\`powershell
node --test .\scripts\tests\worker-issue-101-cloudflare-diagnostic.test.mjs
\`\`\`

Этот локальный mock-тест проверяет байтовую передачу и обработку ошибок.
Он не заменяет реальную проверку Cloudflare runtime и Telegram.

## Что делать по результатам

- Если нет \`ws_message_received\`, но есть \`ws_accepted\`: проверять
  передачу WebSocket-данных клиентом и edge.
- Если \`ws_message_received\` есть, но нет \`tcp_opened\`:
  проверять \`socket.opened\` и ошибки исходящего соединения.
- Если \`tcp_write_completed\` есть, а \`tcp_read_completed\` нет:
  проверять upstream-цель, сетевой путь Cloudflare → сервер,
  а также совместимость первых байтов TCP/TLS.
- Если \`tcp_read_completed\` есть, но клиент не видит данных:
  проверять \`ws_send_completed\`, путь обратного WebSocket и клиента.
- Если часто появляется \`loadShed\` либо \`internal error\`:
  смотреть Cloudflare Workers Metrics, Issues и status-страницу;
  при повторяемости передать Cloudflare Support время (UTC),
  Ray ID и текст reference ID.

**PR #104 остаётся Draft до реального device acceptance:** подключение
Telegram, передача сообщений и загрузка медиа через Legacy Worker.
