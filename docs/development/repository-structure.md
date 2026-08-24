# Структура репозитория

Актуально для опубликованной версии **1.10.12** и текущей разработки **v1.10.13**.

## Корень

| Путь | Назначение |
|------|------------|
| `README.md` / `README_EN.md` | Основное публичное описание проекта |
| `CHANGELOG.md` | История версий |
| `LICENSE` | GPLv3 |
| `.github/workflows/` | Trusted CI, Project Sync и release automation |
| `app/` | Модуль Android-приложения |
| `native/tgwsproxy/` | Go proxy runtime (сборка `libtgwsproxy.so`) |
| `docs/` | Публичная документация проекта |
| `scripts/` | Локальные build/test/release entry point и утилиты |
| `gradle/`, `gradlew*` | Gradle Wrapper |
| `icon.png` | Исходник иконок приложения |

В корне **нет** Go-исходников runtime: они находятся в `native/tgwsproxy/`.

Приватные governance-файлы могут существовать локально, но не являются частью публичного репозитория. В частности, `/AGENTS.md` и `/.project-rules/` должны оставаться локальными и исключаются через `.gitignore`.

## `.github/workflows/`

| Workflow | Назначение |
|---|---|
| `trusted-ci.yml` | Owner-only проверки trusted same-repository PR на self-hosted Windows/X64 runner и ручной dispatch |
| `project-sync.yml` | Добавление owner-created Issues/PR в Development Project #2 |
| `release.yml` | Проверка release tag, повторный CI, сборка `dist/` и публикация GitHub Release |

Подробности и trust model: [github-automation.md](github-automation.md).

## `app/`

Android-приложение: Kotlin, Jetpack Compose UI, `ProxyService`, настройки frontend/route policy, диагностика, логи и JNA bridge к `libtgwsproxy.so`.

Собранная библиотека:

```text
app/src/main/jniLibs/arm64-v8a/libtgwsproxy.so
```

Она генерируется build-процессом и в Git не коммитится.

## `native/tgwsproxy/`

Нативный proxy runtime:

- `tg-ws-proxy.go` — основной runtime/bridge и связанные transport-функции;
- `mtproto_worker_route.go`, `mtproto_ws_route.go` — MTProto route connectors;
- `mtproxyfrontend/` — MTProto listener/frontend;
- `tgwsroute/` — типы маршрутов и связанная route-логика;
- `go.mod`, `*_test.go` — Go module и тесты.

Точный состав runtime меняется по мере развития; архитектурные документы не должны подменять фактическое дерево исходников.

Сборка:

```powershell
.\scripts\build-native-android.ps1
```

Тесты:

```powershell
Push-Location native\tgwsproxy
go test ./...
Pop-Location
```

## `docs/`

| Каталог | Содержимое |
|---------|------------|
| `docs/product/` | Идея проекта, MVP scope, roadmap и другие публичные product docs |
| `docs/architecture/` | Архитектура, tech stack, режимы, CF pool, Worker, уведомления |
| `docs/development/` | Публичная структура репозитория, automation contract и development notes |
| `docs/testing/` | Ручные чеклисты и release smoke checks |
| `docs/releases/` | Release workflow, чеклисты, release notes |
| `docs/versions/` | Документы по отдельным версиям/итерациям |
| `docs/research/` | Публичные технические исследования и сравнения |
| `docs/assets/` | Публичные изображения и screenshots для документации |

### Приватные docs-пути

Следующие пути зарезервированы для локальной работы и не публикуются:

- `docs/ai-prompts/`;
- `docs/private/`;
- `docs/development/development-principles.md` — локальная копия общих принципов разработки, если она используется.

## `scripts/`

| Скрипт | Назначение |
|--------|------------|
| `ci.ps1` | Единый локальный/CI entry point: Go verify/tests, Android tests, debug APK и APK audit |
| `release.ps1` | Signed release APK → signature verification → `dist/` + SHA-256 |
| `build-native-android.ps1` | Go → `libtgwsproxy.so` |
| `build-apk.ps1` | Gradle APK + копия в `artifacts/` |
| `install-apk.ps1` | Установка локально собранного APK через ADB |
| `audit-apk-icons.ps1` | Проверка упакованных icon resources |
| `generate-icons.py` | Иконки из `icon.png` |
| `sync-readme-screenshots.ps1` | Копирование скриншотов в `docs/assets/screenshots/` |
| `cloudflare-worker/worker.js` | Worker script для ручного деплоя |

## Не для Git

| Путь | Причина |
|------|---------|
| `AGENTS.md`, `.project-rules/` | Приватные project governance files |
| `docs/ai-prompts/`, `docs/private/` | Приватные AI/governance материалы |
| `.codex/`, `.cursor/`, `.claude/`, `.ai/` | Локальное состояние AI/dev tools |
| `artifacts/`, `dist/` | Локальные build/release artifacts |
| `runtime-logs/`, `*.log` | Локальные логи и captures |
| `secrets/`, `.env*`, `*.secret`, `*.secrets` | Секреты и локальное окружение |
| `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pem`, `*.key` | Signing/private key material |
| `app/build/`, `.gradle/` | Gradle build outputs |
| `app/src/main/jniLibs/**/*.so` | Сгенерированные native libraries |
| `readme_images/` | Локальная staging-папка для скриншотов |

Источник истины для исключений — `.gitignore`.
