# Ручная проверка после рефакторинга 1.8

Чеклист после переноса Go runtime в `native/tgwsproxy/` и реорганизации `docs/`.

## Сборка и тесты

- [ ] `.\gradlew.bat assembleDebug` — успех
- [ ] `cd native\tgwsproxy` → `go test ./...` — успех (или зафиксировать известные ограничения Windows)
- [ ] `.\scripts\build-apk.ps1 -Configuration Debug` — APK на месте

## Приложение

- [ ] Установка APK, запуск без crash
- [ ] Старт / стоп прокси
- [ ] Уведомление foreground (если включено)

## Маршруты

- [ ] Direct WebSocket (Wi‑Fi, если разрешён policy)
- [ ] Cloudflare Worker (если настроен domain)
- [ ] Cloudflare Proxy
- [ ] TCP fallback при недоступности WS

## Сети

- [ ] Мобильная сеть: policy, логи `route_policy network=MOBILE`
- [ ] Wi‑Fi: policy, переключение/reconfigure

## Настройки и логи

- [ ] Сохранение настроек после перезапуска
- [ ] Runtime-логи / экспорт

## Репозиторий

- [ ] README: скриншоты открываются (`docs/assets/screenshots/`)
- [ ] В `git status` нет `runtime-logs/`, `artifacts/`, `*.jks`, `secrets/`
- [ ] Корень без `*.go` (только `native/tgwsproxy/`)
