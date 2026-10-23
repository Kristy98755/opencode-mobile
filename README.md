# OpenCode Connect

Неофициальный open-source Android-клиент для [OpenCode](https://opencode.ai).
Телефон становится «глазами и руками» для экземпляра OpenCode, который работает
на вашем ПК: чат с живой стримингом, сессии, разрешения, выбор модели и
просмотр файлов — всё удалённо.

```
Android-приложение  <──WebSocket──>  Cloudflare relay  <──WebSocket──>  PC-агент (Node.js)  <──HTTP/SSE──>  opencode serve
```

Телефон **не видит** ваших учётных данных OpenCode — их видит только агент на
ПК. Relay просто пересылит трафик между двумя сокетами с общим pairing-токеном
и ничего не хранит.

## Возможности

- живой стриминг чата: текст, reasoning, вызовы инструментов, статусы активности
- список сессий, переключение между проектами, индикаторы активных сессий
- экраны разрешений (Allow/Deny) и вопросы агента прямо с телефона
- выбор модели и агента
- файлы: просмотр (в чате — рабочая директория этой сессии, с главного экрана —
  домашняя папка, `~` раскрывается агентом), превью до 2 МБ, скачивание на телефон
  (Android 10+ — сразу в `Download/OpenCodeConnect` без лишних вопросов; Android 8-9 —
  через системный пикер), переименование/копирование/перемещение (mv/cp), удаление
  только в корзину Windows с подтверждением слайдером. Операций с правами NTFS
  (chmod/chown/takeown) нет и не будет
- уведомления о новых репликах ассистента
- автоматическое переподключение к relay и фоновый опрос сессий
- агент на ПК: автозапуск `opencode serve`, вотчдог, автозагрузка через задачу
  Windows и иконка в трее вместо консоли

## Состав репозитория

| Папка | Что это |
|---|---|
| `app/` | Android-клиент, Kotlin + Jetpack Compose (пакет `kanami.isa.opencode.connect`) |
| `agent/` | нулевая-зависимость Node.js-мост к локальному `opencode serve`; плюс `ocmtray.cs` — трей-обёртка без консоли |
| `relay/` | Cloudflare Worker + Durable Object: парует один телефон с одним ПК по pairing-токену |

## Установка (короткий путь)

1. Скачайте `OpenCodeConnect-*.apk` из **Releases** и установите
   (Android попросит разрешить установку из неизвестных источников).
2. Откройте приложение, введите relay URL и pairing token, нажмите **Connect**.

> Если раньше стояло приложение с пакетом `app.opencode.mobile` — это уже
> другое приложение. Новое встанет отдельной иконкой, старое можно удалить;
> relay URL и pairing token нужно ввести заново.

## Настройка с нуля

### 1. Разверните relay

```bash
cd relay
npx wrangler deploy
npx wrangler secret put PAIRING_TOKEN   # любая случайная строка, напр. 32 hex-символа
```

Запомните выданный URL вида `https://opencode-mobile-relay.<account>.workers.dev`.

### 2. Запустите агента на ПК

Нужны Node.js 20+ и установленный `opencode`.

```bash
cd agent
copy config.example.json config.json
# раскомментируйте и заполните: relayUrl, token, opencode.url / username / password
node agent.mjs
```

Автозагрузка (переживает перезагрузку и сон):

```powershell
powershell -ExecutionPolicy Bypass -File agent\install_agent_task.ps1
```

Создаёт задачу планировщика `OpenCodeConnectAgent`, которая на входе в систему
поднимает `ocmtray.exe` — иконку в трее: зелёная точка = агент работает,
красная = остановлен. Меню по правой кнопке: Start / Stop / Restart agent,
Open log, Quit. Окно консоли не появляется ни у чего.

Поля `config.json`:

| Поле | Значение |
|---|---|
| `relayUrl` | URL вашего relay (https/wss) |
| `token` | pairing-токен, тот же, что в `PAIRING_TOKEN` |
| `opencode.url` | базовый URL локального `opencode serve` (по умолчанию `http://127.0.0.1:4096`) |
| `opencode.username` / `opencode.password` | Basic-auth serve (если включён) |
| `autoStartServe` | сам поднимать `opencode serve`, если он не запущен |
| `directory` | рабочая директория-подсказка для агента |

### 3. Подключите телефон

В приложении: relay URL + pairing token → **Connect** → создайте сессию.

## Сборка клиента из исходников

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
```

Для подписания задайте свойства `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (см. `app/build.gradle.kts`).

## Безопасность

- relay только пересылает трафик между сокетами с верным pairing-токеном;
  ничего не сохраняет
- используйте только HTTPS/WSS
- владелец pairing-токена может управлять вашим OpenCode — храните его как пароль

## Лицензия

MIT
