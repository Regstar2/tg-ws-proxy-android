# Структура репозитория

Актуально для версии **1.10.12**.

## Корень

| Путь | Назначение |
|------|------------|
| `README.md` | Описание проекта, сборка, ссылки |
| `CHANGELOG.md` | История версий |
| `LICENSE` | GPLv3 |
| `app/` | Модуль Android-приложения |
| `native/tgwsproxy/` | Go proxy runtime (сборка `libtgwsproxy.so`) |
| `docs/` | Документация |
| `scripts/` | Скрипты сборки и утилиты |
| `gradle/`, `gradlew*` | Gradle wrapper |
| `icon.png` | Исходник иконок приложения |

В корне **нет** Go-исходников: они перенесены в `native/tgwsproxy/`.

## `app/`

Android-приложение: Kotlin, Jetpack Compose UI, `ProxyService`, настройки frontend/route policy, логи, JNI/JNA к `libtgwsproxy.so`.

Собранная библиотека: `app/src/main/jniLibs/arm64-v8a/libtgwsproxy.so` (генерируется, в git не коммитится).

## `native/tgwsproxy/`

Нативный proxy runtime:

- `tg-ws-proxy.go` — SOCKS5, MTProto lifecycle, bridge, CGO exports
- `routing.go`, `adaptive_bridge.go`, `proxy_status_bridge.go`
- `mtproxyfrontend/` — MTProto listener/frontend
- `tgwsroute/` — типы маршрутов, adaptive, CF pool
- `go.mod`, `*_test.go`

Сборка: `scripts/build-native-android.ps1` (вызывается из Gradle `preBuild`).

Тесты:

```powershell
cd native\tgwsproxy
go test ./...
```

## `docs/`

| Каталог | Содержимое |
|---------|------------|
| `docs/architecture/` | Архитектура, режимы, CF pool, worker, уведомления |
| `docs/development/` | Структура репозитория, заметки для разработки |
| `docs/testing/` | Ручные чеклисты |
| `docs/releases/` | Release workflow, чеклисты, RELEASE_NOTES |
| `docs/assets/screenshots/` | Скриншоты для README |

## `scripts/`

| Скрипт | Назначение |
|--------|------------|
| `build-native-android.ps1` | Go → `libtgwsproxy.so` |
| `build-apk.ps1` | Gradle APK + копия в `artifacts/` |
| `generate-icons.py` | Иконки из `icon.png` |
| `sync-readme-screenshots.ps1` | Копирование скринов в `docs/assets/screenshots/` |
| `cloudflare-worker/worker.js` | Worker script для ручного деплоя |

## Не для git

| Путь | Причина |
|------|---------|
| `artifacts/` | Локальные APK и `.so` |
| `runtime-logs/` | Локальные логи |
| `secrets/`, `*.jks`, `*.keystore` | Секреты и ключи |
| `app/build/`, `.gradle/` | Сборка Gradle |
| `readme_images/` | Опциональная локальная staging-папка для скринов |

См. `.gitignore`.
