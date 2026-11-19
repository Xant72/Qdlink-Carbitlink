# Конфиденциальность / Privacy — OvertakeLink

[English below](#english)

OvertakeLink (`io.github.queryparams.overtakelink`) **не собирает, не хранит у себя на серверах и никому
не передаёт** данные пользователя. У проекта нет серверов, аналитики и рекламы.

## Разрешения и для чего они

- **Микрофон.** Используется, только когда Android Auto открывает голосовой ввод (Ассистент,
  ответ на сообщение). Звук передаётся в Android Auto на этом же телефоне и не сохраняется.
- **Уведомления.** Значок работы в шторке с кнопкой «Остановить» и подсказки (сервер Android Auto выключен, ГУ не
  видит телефон).
- **Местоположение.** OvertakeLink его не запрашивает: навигация Android Auto использует GPS телефона
  напрямую. Встроенный приёмник Android Auto (из Open Headunit) умеет передавать координаты в
  Android Auto, если вы сами выдадите разрешение в его расширенных настройках; тогда последняя
  позиция хранится в настройках приложения на телефоне (для ночного режима и быстрого старта) и
  никуда не отправляется, в журнал координаты не попадают. Удаляется вместе с приложением.
- **USB и Bluetooth.** Связь с головным устройством машины. Из кнопок руля OvertakeLink принимает только
  голосовую (если машина передаёт её по Bluetooth) — чтобы открыть Ассистента; «вперёд/назад» идут от машины к
  телефону по Bluetooth напрямую.
- **Установка приложений.** Только для обновления самого OvertakeLink по кнопке «Скачать и
  установить»; Android спрашивает это разрешение отдельно.

## Журнал работы

OvertakeLink ведёт журнал внутри приложения, чтобы можно было разобраться с проблемой. Копии (до 5)
сохраняются в `Загрузки/OvertakeLink` по «Сохранить лог» и после каждого сеанса по кабелю; «Очистить» эти копии не
удаляет. В журнале есть модель телефона, служебные сообщения головного устройства и встроенного приёмника Android
Auto, при выводе в GAP Smart Tool — IP-адрес ГУ в сети телефона и, среди прочего, **Bluetooth-адрес машины**. Журнал никуда не отправляется сам — только если вы приложите его
к сообщению. Перед публикацией в открытом доступе (например, в Issues на GitHub) адрес можно
удалить из файла.

## Сеть

- Android Auto и OvertakeLink общаются внутри телефона (localhost).
- При выводе в GAP Smart Tool OvertakeLink передаёт картинку Android Auto на ГУ через точку доступа телефона
  (TCP-порт 47621) и принимает касания; подключения из сетей с выходом в интернет отклоняются.
- Для проверки обновлений OvertakeLink при открытии (не чаще раза в 10 минут) и по кнопке «Проверить обновления» обращается к GitHub (`api.github.com`). При этом
  GitHub, как любой сайт, видит IP-адрес телефона, а приложение сообщает ему только своё название и версию.
- По кнопке «Скачать и установить» файл новой версии скачивается с GitHub (загрузчиком Android, если он не отключён);
  файл лежит в папке приложения и удаляется при следующем открытии OvertakeLink после установки или когда выйдет
  версия новее.
- «Поддержать» (только в русском интерфейсе) открывает экран поддержки в приложении; кнопка «Перевести по СБП» на
  нём открывает страницу перевода на сайте Сбербанка (в браузере или банковском приложении — как настроен телефон);
  приложение само ничего туда не передаёт.
- Карта выбора места в расширенных настройках встроенного приёмника загружает фрагменты карты с OpenStreetMap
  (`tile.openstreetmap.org`): сервер видит IP-адрес телефона и показываемый участок карты.
- Данные, которые обрабатывает сам Android Auto, регулируются политикой Google.

## Вопросы

[Issues проекта на GitHub](https://github.com/queryParams/OvertakeLink/issues).

---

## English

OvertakeLink (`io.github.queryparams.overtakelink`) **does not collect, store on its own servers or
share** user data. The project has no servers, analytics or ads.

### Permissions and what they are for

- **Microphone.** Used only when Android Auto opens voice input (the Assistant, replying to a
  message). The audio goes to Android Auto on the same phone and is not stored.
- **Notifications.** The status icon with the “Stop” button and hints (Android Auto server is off, the head unit
  does not see the phone).
- **Location.** OvertakeLink does not request it: Android Auto navigation uses the phone’s GPS
  directly. The built-in Android Auto receiver (from Open Headunit) can pass coordinates to Android
  Auto if you grant the permission yourself in its advanced settings; the last position is then kept
  in the app’s settings on the phone (for night mode and a faster start), is not sent anywhere and
  never goes into the log. It is deleted together with the app.
- **USB and Bluetooth.** Connection to the car’s head unit. Of the steering wheel buttons,
  OvertakeLink receives only the voice button (if the car sends it over Bluetooth) to open the Assistant;
  next/previous go from the car to the phone over Bluetooth directly.
- **Installing apps.** Only to update OvertakeLink itself with the “Download and install” button;
  Android asks for this permission separately.

### Log

OvertakeLink keeps a log inside the app to help troubleshoot problems. Copies (up to 5) are saved to
`Downloads/OvertakeLink` by “Save log” and after each cable session; “Clear” does not delete these copies. The log
contains the phone model, service messages from the head unit and the built-in Android Auto receiver, with output to
GAP Smart Tool the head unit’s IP address on the phone’s network and, among others, **the car’s Bluetooth address**. The log is never sent by itself — only if you attach it to a report. Before publishing it
(for example, in GitHub Issues) you can remove the address from the file.

### Network

- Android Auto and OvertakeLink talk inside the phone (localhost).
- With output to GAP Smart Tool, OvertakeLink sends the Android Auto picture to the head unit over the phone’s Wi-Fi
  hotspot (TCP port 47621) and accepts touches back; connections from networks with internet access are refused.
- To check for updates, OvertakeLink contacts GitHub (`api.github.com`) when opened (at most once every 10 minutes) and with the “Check for updates” button. Like any
  website, GitHub sees the phone’s IP address; the app sends only its name and version.
- With the “Download and install” button, the new version’s file is downloaded from GitHub (by Android’s download
  manager unless it is disabled); the file stays in the app’s folder and is deleted the next time OvertakeLink opens
  after the installation, or once a newer version is out.
- “Support” (Russian interface only) opens a support screen in the app; its SBP transfer button opens the
  Sberbank transfer page (in the browser or a banking app, depending on the phone); the app itself sends nothing
  there.
- The location picker map in the built-in receiver’s advanced settings loads map tiles from OpenStreetMap
  (`tile.openstreetmap.org`): the server sees the phone’s IP address and the map area shown.
- Data processed by Android Auto itself is governed by Google’s policy.

### Questions

[Project Issues on GitHub](https://github.com/queryParams/OvertakeLink/issues).
