# Переотправка в F-Droid: чек-лист

Контекст: MR [!54830](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54830) закрыт
29.10.2026 ревьюером linsui по трём причинам: не заполнен шаблон MR, нет трёх месяцев
разработки (Quality point 6), «нет уникальных функций». Первые две причины закрываются
процедурой, третья — аргументами (см. `mr-description.md`, раздел «Unique value»).

## Когда отправлять

- Не раньше **26 января 2027**: первый публичный коммит — 26.10.2026, политика требует
  «активно разрабатывается минимум 3 месяца».
- Лучше **первая неделя февраля 2027**, сразу после релиза 1.2.6: свежий релиз показывает,
  что проект живой, и даёт новый changelog для fastlane.

## 1. Держать проект активным до отправки

- Релиз раз в 3–4 недели: версия, тег `vX.Y.Z`, `changelogs/<versionCode>.txt` в обоих
  языках (`en-US`, `ru-RU`).
- История коммитов и тегов должна покрывать весь период с 26.10.2026 без длинных пауз —
  именно её смотрит ревьюер.

## 2. Собрать доказательства пользователей

Нужны для раздела «Development history» (и на случай, если трёх месяцев покажется мало):

```powershell
# скачивания релизов и звёзды
Invoke-RestMethod "https://api.github.com/repos/ArhonTs/FireDrop/releases" |
  ForEach-Object { "$($_.tag_name): " + (($_.assets | ForEach-Object { "$($_.name)=$($_.download_count)" }) -join ', ') }
(Invoke-RestMethod "https://api.github.com/repos/ArhonTs/FireDrop").stargazers_count
```

- Отзывы/вопросы из Telegram @LoioshS — достаточно пары цитат или скриншота.
- Вписать числа вместо `NN` и `SS` в `metadata/mr-description.md`.

## 3. Скриншоты Android-приложения

Сейчас в описании MR есть Windows и телефонная веб-страница, но **нет экранов самого
Android-приложения** — без них шаблон формально не закрыт.

- Сделать 3–4 снимка: главный экран (QR + шестизначный код), процесс передачи со
  скоростью, настройки с выбором языка, экран «О приложении».
- Сохранить в `verification/` (например `android-main.png`, `android-transfer.png`,
  `android-settings.png`).
- Вставить блок с картинками в `metadata/mr-description.md` вместо `<!-- TODO -->`.

## 4. Обновить метаданные

`metadata/ru.firedrop.app.yml`:

- `versionName`, `versionCode` — версия релиза;
- `CurrentVersion`, `CurrentVersionCode` — то же самое;
- `commit` — **полный хеш**, не тег:

```powershell
git rev-list -n 1 v1.2.6
```

- оставить **один** блок `Builds` и только последнюю версию;
- новые changelog-файлы в `Android/fastlane/metadata/android/{en-US,ru-RU}/changelogs/`
  с именем, равным `versionCode`.

## 5. Проверить перед отправкой

- YAML парсится и содержит обязательные поля (`Categories`, `License`, `SourceCode`,
  `Summary`, `Description`, `Repo`, `Builds`, `AutoUpdateMode`, `UpdateCheckMode`,
  `CurrentVersion`, `CurrentVersionCode`).
- Окончания строк — LF (проверить: `git cat-file -p HEAD:metadata/ru.firedrop.app.yml`
  не должен содержать `\r`).
- Лимиты fastlane: title ≤ 30, short description ≤ 80, full description ≤ 4000,
  changelog ≤ 500 символов.
- `dist/`, `Android/build/`, ключи и APK не попали в git.
- Тег релиза виден анонимно: `git ls-remote --tags https://github.com/ArhonTs/FireDrop.git`.

## 6. Ветка и форк

- Форк `wmidxru/fdroiddata` должен быть публичным, ветка — не защищённой.
- Старую ветку `firedrop-ru.firedrop.app` удалить или создать новую от свежего `master`:
  переиспользовать старую не нужно, там метаданные 1.2.5.

## 7. Создать MR

- Только **новый** MR, закрытый !54830 не переоткрывать.
- Проще через веб-интерфейс GitLab: при создании MR подставится `Default.md`, метка
  `~"New App"` применится сама.
- Заголовок: `New app: FireDrop (ru.firedrop.app)`.
- Описание скопировать из `metadata/mr-description.md`.
- Картинки: либо drag & drop в редакторе, либо ссылки на уже загруженные файлы
  (`https://gitlab.com/-/project/88258796/uploads/...`); при новых скриншотах — загрузить
  в форк и взять `full_path` из ответа API.
- Строку про CI не удалять: в шаблоне прямо написано, что при запросе телефона или карты
  нужно оставить примечание, а не проходить верификацию.

## 8. После отправки

- Следить за комментариями: типичные просьбы — `srclibs` вместо загрузки zxing,
  reproducible builds, дополнительные скриншоты.
- F-Droid подписывает APK своим ключом: сборка из каталога не установится поверх
  `FireDrop-<version>.apk` с подписью `firedrop.jks`. Пользователям нужно удалить старую
  версию — упомянуть в релизе.

## Полезные ссылки

- Политика включения: https://f-droid.org/docs/Inclusion_Policy (Quality point 6).
- Шаблон MR: `.gitlab/merge_request_templates/Default.md` в fdroiddata.
- Закрытый MR с причинами: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54830
