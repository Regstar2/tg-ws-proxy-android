# Issue #162 — локальная проверка ARMv7 / ARM64 / Universal

Дата: 17 ноября 2026. Изменения и артефакты существуют только локально.
Issue и комментарий прочитаны; push, PR, комментарии, теги, публикация и удалённый
CI не выполнялись. Это проверка локального кандидата, а не опубликованный релиз.


Уточнение версии: первая сборка ошибочно использовала устаревшие локальные метаданные
1.11.8/64. По замечанию владельца проверены main и GitHub Releases: v1.11.9/65
опубликована 16 ноября 2026. В рабочую ветку локально влит main, три APK пересобраны
и повторно проверены. Device runtime evidence ниже получено на первоначальном
изолированном Debug APK; нативные Go-библиотеки в новых Release 1.11.9 побайтово
совпадают с испытанными. Повторные unit/lint/build относятся к 1.11.9.

## Реализация

- Go/CGO shared library для `armeabi-v7a` (`GOARCH=arm`, `GOARM=7`) и
  `arm64-v8a` (`GOARCH=arm64`), Android API 26.
- Native script проверяет ELF и публикует библиотеки в jniLibs после успешной
  сборки обеих ABI. При ошибке устаревшие output `.so` недоступны для упаковки.
- Gradle ABI splits создают отдельные ARMv7/ARM64 APK и Universal для Debug/Release.
  Universal содержит обе версии Go и JNA; x86/x86_64 не включаются.
- `applicationId=io.github.regstar2.tgwsproxy`, `minSdk=26`, версия `1.11.9`,
  Release `versionCode=65` сохранены. Debug имеет versionCode 66 и суффикс `-debug`.
- Опциональная сборка `-PisolatedAbiSmoke=true` создаёт Debug-пакет
  `io.github.regstar2.tgwsproxy.abismoke` для независимых настроек и безопасных тестов.
- Updater выбирает совместимый официальный артефакт по порядку
  `Build.SUPPORTED_ABIS`, затем Universal. Проверяет фактические ELF и наличие
  Go/JNA. HTTPS/source, SHA-256, подпись, signer установленного приложения,
  package name, повышение versionCode и minSdk остаются обязательными.
- Build/release/audit/CI и локальный исходник release workflow поддерживают три
  APK + три checksum. Исторический локальный alias APK теперь указывает на Universal.

Сетевая маршрутизация и Go runtime исходники в рамках #162 не изменялись.
Базовый локальный commit уже содержит отдельные изменения #156; они не являются
результатом этой задачи и требуют собственной сетевой приёмки.

## Среда

| Компонент | Фактическое значение |
|---|---|
| Host | Windows 11, локальный PowerShell; WHPX доступен |
| Android Studio | существующая установка `C:\Program Files\Android\Android Studio` |
| JDK | Microsoft OpenJDK 17.0.20.1 |
| Go | 1.26.2 windows/amd64; module go directive 1.25.0 |
| Gradle / AGP / Kotlin | 8.2.1 / 8.2.2 / 1.9.22 |
| SDK | `C:\Android\Sdk`, compile/target35, build tools35.0.1 для финального аудита |
| NDK | 28.2.13676358; Android clang API26 |
| Emulator | установлен официальный 37.2.13, Windows Hypervisor Platform |
| Основной AVD | `TgWsProxy_Issue162_API30_x86`, Google APIs API30 x86 revision16, 2 GiB RAM, 4 cores, no-window, swiftshader_indirect |
| AVD API30 ABI | `x86,armeabi-v7a,armeabi`; Native Bridge `libndk_translation.so` |
| Дополнительный AVD | `TgWsProxy_Issue162_API28_x86`, Google APIs API28 x86 revision12; отдельный от API30 и пользовательских AVD |
| Физический телефон | Xiaomi 2109119DG (lisa), Android14/API34; `arm64-v8a,armeabi-v7a,armeabi` |

Использованы существующие SDK/NDK/Go/JDK/Studio. Дополнительно установлены Emulator
и два официальных system image. Загрузка образа Java SDK Manager зависла;
официальные ZIP загружены через HTTPS с `dl.google.com` и распакованы в SDK.
SHA1 API28 ZIP проверен по официальному XML manifest:
`e0541ac9b783ab91a00054e133bda340a2b9c757`.
Существующие пользовательские AVD, Windows Security и системная виртуализация не менялись.

## Проверки

Локальные доказательства находятся в `C:\Base\projects\TGWSProxyAndroid\artifacts\issue-162`.
Эти файлы и APK исключены из Git; таблица перечисляет реально выполненные проверки.

| Проверка | Результат | Среда и доказательства |
|---|---|---|
| ARMv7 / ARM64 CGO build | PASS | Gradle native task; `native-*-elf.txt`: ELF32/ARM и ELF64/AArch64; обязательные экспортируемые функции найдены |
| APK ZIP/ELF/JNA | PASS | `release-1.11.9-{abi}.json`, audit-apk-abis.py; только libtgwsproxy.so и libjnidispatch.so на каждую ABI |
| APK metadata / signatures / SHA256 | PASS | `release-1.11.9-*-badging.txt`, `release-1.11.9-*-signature.txt`, `apk-manifest-1.11.9.json`; minSdk 26, package/version, release signer |
| Release native vs испытанный Debug native | PASS | Побайтовые SHA256 библиотек Go совпадают для обеих ABI |
| Android unit tests | PASS | 430 tests, 0 failures/errors; `gradle-1.11.9.txt`, app/build/test-results/testDebugUnitTest |
| lintDebug / assembleDebug / assembleDebugAndroidTest | PASS | `gradle-1.11.9.txt`, BUILD SUCCESSFUL |
| Signed assembleRelease + release.ps1 | PASS | `release-1.11.9.txt`; все три APK + SHA256 |
| Native failed-build regression | PASS | Исходный скрипт: RED, сохранял stale output; текущий: GREEN под Windows PowerShell5.1 и pwsh; `native-failure-red.txt` |
| Python APK audit regressions | PASS | 2 unittest cases, включая wrong ELF, missing JNA, extra ABI |
| ARMv7 APK JNA/Go + listener + restart + SOCKS5 echo + lifecycle | PASS | Xiaomi, actual 32-bit, JNA pointer4; `phone-armv7-final.txt` и соответствующий smoke result.json |
| ARM64 APK JNA/Go + listener + restart + SOCKS5 echo + lifecycle | PASS | Xiaomi, actual 64-bit, JNA pointer8; `phone-arm64-final.txt` |
| Universal в 32-битном процессе | PASS | Xiaomi, `adb install --abi armeabi-v7a`; `phone-universal-armv7.txt` |
| Universal в 64-битном процессе | PASS | Xiaomi, `adb install --abi arm64-v8a`; `phone-universal-arm64.txt` |
| Все Go test packages на ARMv7 | PASS | Кросс-компиляция на Windows, выполнение на Xiaomi; `go-armeabi-v7a/{runtime,mtproxyfrontend,tgwsroute}.txt` |
| Все Go test packages на ARM64 | PASS | Аналогично; `go-arm64-v8a/{runtime,mtproxyfrontend,tgwsroute}.txt` |
| Windows host go test ./... | BLOCKED | Windows Security блокирует создание mtproxyfrontend.test.exe; root и tgwsroute проходят; `go-retry.txt` |
| scripts/ci.ps1 | BLOCKED | Пройденные предыдущие PS/audit checks, go mod verify; остановка на той же блокировке Windows Security, `ci.txt`. Android checks выполнены отдельно |
| Release audit RU/EN/private files | PASS | 1209 default / 1209 RU resources, audit-release.ps1 под pwsh и PS5.1; `release-audit*.txt` |
| APK application identity / icons | PASS | `identity.txt`, `icons.txt` |
| release.yml syntax | PASS | YAML parse и PowerShell parser для всех 9 run-блоков; workflow не запускался |
| Независимое ревью | PASS | Блокирующих дефектов не найдено; исправлена ложная ARM64 подпись размера обновления в RU/EN |
| API30 ARM Native Bridge runtime | FAIL / BLOCKED для приёмки эмулятора | ARMv7 устанавливается, pointer4 подтверждён, первый Go CGO call вызывает SIGSEGV; `emulator-smoke-first.txt`, `emulator-native-crash.txt` |
| API28 ARM Native Bridge runtime, ARMv7 и Universal | FAIL / BLOCKED для приёмки эмулятора | `x86,armeabi-v7a,armeabi`, libndk_translation.so; фактический 32-bit процесс / JNA pointer4, затем SIGABRT; `emulator28-smoke.txt`, `emulator28-native-crash.txt`, `emulator28-universal.txt` |

На каждой ABI телефона тест Go packages включает локальные фикстуры MTProto,
WebSocket/TLS, Cloudflare/Worker и AWG connector policy. Это не подтверждение
доступности внешних сервисов. Instrumentation отдельно передаёт 8192 байта
через SOCKS5 → loopback echo, проверяет статус и двукратный запуск/остановку.
Для lifecycle запускается MainActivity и foreground service на свободном порту,
выполняется переход в фон и возврат, затем сервис останавливается.
На MIUI foreground launch сделан через shell после старта instrumentation;
таймаут первоначального startActivitySync сохранён как диагностический неуспешный запуск.

## APK

Все три файла подписаны существующим Release RSA4096 ключом, APK Signature Scheme v2.
Certificate SHA256:
`ae55265059933a7b495bd57bc571d294a1850609f56e89117eddf682faae2c6d`.
Версия 1.11.9, versionCode 65, package `io.github.regstar2.tgwsproxy`, minSdk 26.

| Файл под `C:\Base\projects\TGWSProxyAndroid\dist\` | Размер, байт | SHA256 |
|---|---:|---|
| TgWsProxy-Android-v1.11.9-armeabi-v7a.apk | 19490474 | `d089d478a203e26fcc0f7f8ea380c157e130fb8f32a9442f26cd24c48c880b45` |
| TgWsProxy-Android-v1.11.9-arm64-v8a.apk | 20188182 | `1e5742fcb0bb1b893c0020439a71309c7e1390ad3bbdfcdb54eb06561d6479cc` |
| TgWsProxy-Android-v1.11.9-universal.apk | 36841282 | `dbab1d3780b89a6a1f50ce38f89beef204ecfc45a8bf5cbb03bb27764c1fc11c` |

Каждому APK соответствует соседний `.sha256`. Старые файлы v1.11.5 в dist сохранены. Ошибочно собранные ранее APK v1.11.8
перенесены в artifacts/issue-162/superseded-1.11.8 и не являются текущим результатом. APK проверены на отсутствие key/keystore
файлов; release audit не обнаружил новых credential/private-key signatures в исходниках.
Ключи подписи и пароли не записывались в отчёт или Git.

## Git и ограничения

Полная программная эмуляция ARM32 также оценена: официальный SDK catalog
предлагает armeabi-v7a system images максимум для API25, ниже minSdk 26.
Подходящего официального ARM32 образа для запуска этого приложения не найдено.
Падение Native Bridge нельзя считать PASS; рабочая ARMv7-приёмка выполнена на
физическом устройстве, без трансляции.

Ветка: `feat/162-armv7-universal`, исходный HEAD `7b542b3`. Исходные неотслеживаемые
`.wrangler/` и `local.sync-conflict-20260919-120143-WQMJ727.properties` сохранены.
После исправления версии локально влита актуальная база main `7403f56`
(1.11.9/65). Сетевые исходники совпадают с прежней базой. Запрещённые remote write
операции не выполнялись. Реализация сохранена локальным commit `84aa98e`. Последующие уточнения отчёта
можно увидеть через `git log`. После проверки оба тестовых эмулятора остановлены;
их AVD сохранены для повторного запуска.

Изменённые группы: Gradle/native/APK/release/CI scripts и workflow; updater selector,
installer и их вызовы; RU/EN size label; unit/instrumentation tests; README RU/EN,
CHANGELOG, ABI build/architecture/development/testing docs. Нативная маршрутизация
не редактировалась.

**NOT VERIFIED:** полноценный Telegram-трафик через direct_ws / cf_proxy_ws /
cf_worker_ws / tcp_fallback / AWG-WARP на мобильной сети и Wi-Fi, внешнее WARP
provisioning и переключение живых Telegram-сессий. Системные сетевые настройки
телефона не переключались. Production-пакет и его данные не заменялись;
испытания проведены на отдельном `.abismoke` пакете.

**NOT VERIFIED:** обновление с предыдущей production-версии через системный
установщик. Сохранены и покрыты тестами проверки совместимости/выбора APK;
полный update flow не подменяется unit tests. Перед будущей публикацией требуется
новая версия/versionCode по release policy: локальный кандидат теперь имеет 1.11.9/65. С уже установленной официальной
1.11.9/65 updater не предложит APK с тем же versionCode. Для отдельного будущего
релиза ARMv7 потребуется более высокий versionCode.

ARMv7 APK пригоден для ограниченного тестирования на старом телефоне с Android 8+
и ABI armeabi-v7a. Реальный ARM32 runtime подтверждён на Xiaomi; это не приёмка
всех старых моделей, API26 или всех внешних маршрутов. Публикация как полностью
проверенного стабильного релиза этим отчётом не разрешается.

## Воспроизведение

См. [android-abis.md](../development/android-abis.md) и скрипты
`setup-issue-162-emulator.ps1`, `issue-162-armv7-smoke.ps1`,
`issue-162-native-tests.ps1`. Они используют явный serial и ограниченные ожидания.

Официальные ссылки, использованные для конфигурации:
[ABI splits](https://developer.android.com/build/configure-apk-splits),
[ARM translation in Emulator](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html),
[Emulator command line](https://developer.android.com/studio/run/emulator-commandline).
