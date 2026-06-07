# EpubEdit

Android-приложение для импорта, редактирования и экспорта книг в формате EPUB.

## Что умеет

**Импорт**
- Открывает `.epub` и `.fb2` файлы
- Разбирает `container.xml` → `content.opf` → `toc.ncx`, восстанавливая иерархию глав
- Извлекает метаданные (название, автор, описание) и обложку
- Сохраняет все медиафайлы книги локально в `filesDir/epub_media/`

**Редактор**
- Три экрана: **Библиотека** → **Детали книги** → **Редактор главы**
- В деталях книги — 4 вкладки: Файлы, Главы, Инфо, Статистика
- Редактор отображает главу как последовательность блоков: текст и изображения
- Поддержка inline-иллюстраций (растр + SVG)
- Создание новых глав вручную, удаление, изменение порядка

**Статистика**
- Подсчёт слов и символов для каждой главы и всей книги
- Работает в реальном времени; поддерживает кириллицу, латиницу, составные слова (`кто-то`, `it's`)
- HTML-теги перед подсчётом вырезаются

**Экспорт**
- Собирает отредактированный текст, структуру и изображения обратно в валидный `.epub`

## Архитектура

```
MVVM + Clean Architecture
│
├── data/
│   ├── Entities.kt       # Title, SourceFile, Chapter (Room @Entity)
│   ├── BookDao.kt        # Flow-запросы + транзакции
│   └── BookRepository.kt # прослойка между DAO и ViewModel
│
├── viewmodel/
│   └── BookViewModel.kt  # StateFlow для titles / chapters / editingChapter
│
├── ui/screens/
│   ├── LibraryScreen.kt  # сетка книг, добавление, удаление
│   ├── DetailsScreen.kt  # вкладки: файлы, главы, инфо, статистика
│   └── EditorScreen.kt   # блочный редактор главы
│
└── util/
    ├── EpubProcessor.kt  # парсинг и экспорт EPUB (ZIP + XML DOM)
    ├── BookConverter.kt  # конвертация FB2 → EPUB
    └── WordStatsHelper.kt# подсчёт слов и символов
```

**База данных:** три таблицы — `titles`, `source_files`, `chapters`. `SourceFile` и `Chapter` связаны с `Title` через `ForeignKey CASCADE`.

**Навигация:** Compose NavHost с маршрутами `library` → `details/{titleId}` → `editor/{chapterId}`.

## Стек

| Слой | Библиотека |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Навигация | Navigation Compose |
| ViewModel | AndroidViewModel + StateFlow |
| БД | Room (SQLite) + KSP |
| Изображения | Coil |
| Асинхронность | Coroutines + Flow |
| Язык | Kotlin |

## Сборка

**Требования:** Android Studio (последняя стабильная), SDK 24+, Gradle 8+

```bash
git clone https://github.com/your-username/EpubEdit.git
cd EpubEdit
```

Откройте в Android Studio, дождитесь синхронизации зависимостей, нажмите **Run**.

Или через терминал:

```bash
./gradlew assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`

## Лицензия

[MIT](./LICENSE)
