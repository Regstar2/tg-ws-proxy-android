# Roadmap

## Текущая база

Опубликованная версия: `1.10.12`.

Основной пользовательский сценарий — локальный MTProto Proxy с маршрутизацией через `cf_proxy_ws`; SOCKS5/WebSocket остаётся режимом совместимости. Приложение также содержит `direct_ws`, `cf_worker_ws` и `tcp_fallback`, диагностику маршрутов, Fake TLS и отдельные политики для типов сети.

## v1.10.13 — единая стабилизационная итерация

Все крупные работы ниже относятся к одной версии `v1.10.13`, но выполняются отдельными Issue/PR.

### 1. Runtime stability + Flowseal parity

Issue: #2

- классифицировать наблюдаемую нестабильность по runtime diagnostics;
- сравнить Android runtime с актуальными изменениями `Flowseal/tg-ws-proxy`;
- выборочно перенести изменения, которые действительно применимы к Go/Android реализации;
- усилить regression tests и ручной stress-check на Wi-Fi/мобильной сети;
- не заменять Android-specific hardening слепым копированием upstream.

### 2. Public project structure / documentation

Issue: #3

- добавить `docs/product/`;
- зафиксировать product idea, MVP scope и roadmap;
- добавить публичный tech-stack document;
- привести `.gitignore` к правилам приватных governance-файлов;
- проверить architecture/testing/version/release docs и публичные README.

### 3. GitHub automation

Issue: #4

- trusted CI для self-hosted runner;
- локальный `scripts/ci.ps1` как источник project-specific checks;
- Project sync;
- release workflow + `scripts/release.ps1`;
- запрет выполнения недоверенного fork-кода на self-hosted runner.

### 4. Feedback

Issue: #5

- in-app вход в feedback;
- Report bug / Request feature;
- GitHub Issue Forms;
- безопасный prefill без автоматической публикации логов, секретов или proxy credentials;
- RU/EN strings.

### 5. Update delivery

Issue: #6

- отображение текущей версии;
- ручная и безопасная асинхронная проверка GitHub Releases;
- корректная обработка stable/prerelease;
- уведомление о новой версии и открытие официальной release page;
- никакой зависимости proxy startup/runtime от update check.

### 6. Release / localization / compliance audit

Issue: #7

- RU/EN parity и hardcoded user-facing strings;
- версия, changelog, release notes и README;
- секреты/приватные файлы;
- полный automated + manual release gate;
- финальная проверка Telegram на Wi-Fi и мобильной сети.

## Порядок интеграции

Рекомендуемый порядок внутри `v1.10.13`:

1. #3 — структура/документация;
2. #2 — runtime stability;
3. #4 — automation;
4. #5 — feedback;
5. #6 — update delivery;
6. #7 — финальный release gate.

Runtime-задача #2 остаётся главным техническим риском версии; документационные и repository-management задачи не должны маскировать её результаты.

## После v1.10.13

Следующие версии формируются только из подтверждённых потребностей после стабилизации `v1.10.13`. Потенциальные направления не считаются обещанным scope:

- дальнейшая оптимизация Worker route/pool;
- расширение набора поддерживаемых ABI, если это оправдано;
- улучшение диагностики и автоматического выбора маршрута;
- улучшение release/update delivery после накопления эксплуатационных данных.

## Не планируется как обязательное направление

- превращение приложения в system-wide VPN;
- маршрутизация трафика всех приложений;
- зависимость от одного внешнего домена или одного Cloudflare Worker;
- публикация `.project-rules/`, `AGENTS.md`, `docs/ai-prompts/` или других локальных governance-файлов.
