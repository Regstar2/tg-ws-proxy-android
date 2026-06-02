# Release notes v1.8.0

**Релиз обслуживания репозитория.** Намеренных изменений маршрутизации/runtime для этого тега нет.

`versionName` **1.8.0** · `versionCode` **36**

## С v1.6.0 (уже в этой линейке сборок)

- Политики маршрутов отдельно для Wi‑Fi / Mobile (`NetworkRoutePolicy`)
- Переключатели: `direct_ws`, `cf_worker_ws`, `cf_proxy_ws`, `tcp_fallback`
- Предпочитаемый маршрут и fallback для типа сети
- Автоматический reconfigure при Wi‑Fi ↔ Mobile
- Диагностика эффективной политики и route probe
- Метрики пулов Worker / CF в UI и экспорте
- Постоянные runtime-логи и безопасный экспорт
- Токены политики маршрутов в native runtime (1.7.9.2)
- Route kind отделён от транспорта WebSocket в UI (1.7.9.3)
- Устаревшие события Direct/Worker не перезаписывают текущий маршрут
- Более безопасные дефолты и пресет «Рекомендуемый» (1.7.9.1)
- Убран общий домен CF `pclead.co.uk` — свой домен или встроенный/кэшированный пул

## Что изменилось в v1.8.0 (только репозиторий)

- Go runtime в `native/tgwsproxy/`
- Структура документации: `docs/architecture/`, `docs/development/`, `docs/testing/`, `docs/releases/`, `docs/assets/screenshots/`
- Обновлены README, `CHANGELOG.md`, release checklist, ручной чеклист 1.8
- Усилен `.gitignore`: `artifacts/`, `runtime-logs/`, логи, секреты, keystores

## Поведение runtime (v1.8.0)

Новых фич и изменений маршрутизации для тега v1.8.0 не добавлялось. Поведение соответствует линейке 1.7.9.x на `main`.

## Установка

Установка поверх предыдущего APK. При несовпадении подписи — удалите старый APK и установите снова.

## Проверка сборки

- `cd native/tgwsproxy && go test ./tgwsroute/...` (тесты main на Windows могут падать; используйте Linux/CI)
- `.\gradlew.bat assembleDebug`
- Release APK: `.\scripts\build-apk.ps1 -Configuration Release` с локальным `release-signing.env` (не в git)
