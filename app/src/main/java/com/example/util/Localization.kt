package com.example.util

object Loc {
    private val ru = mapOf(
        "app_name" to "EpubEdit",
        "no_titles" to "Нет добавленных тайтлов",
        "press_add_button" to "Нажмите на кнопку + , чтобы создать новый проект",
        "new_title" to "Новый тайтл",
        "enter_title" to "Введите название книжного проекта или оригинального тайтла:",
        "title_label" to "Название тайтла",
        "title_placeholder" to "Например: Beyond the Event Horizon",
        "create" to "Создать",
        "cancel" to "Отмена",
        "delete_title" to "Удалить тайтл",
        "no_author" to "Автор не указан",
        "created_at" to "Создан: ",
        "add_title" to "Добавить тайтл",
        "settings" to "Настройки",
        "language" to "Язык",
        "english" to "English (Английский)",
        "russian" to "Русский (Russian)",
        "auto_close" to "Автозакрытие HTML-тегов",
        "close" to "Закрыть",
        // Details tabs and fields
        "tab_files" to "Файлы",
        "tab_chapters" to "Главы",
        "tab_info" to "Инфо",
        "tab_stats" to "Статистика",
        "back" to "Назад",
        "save" to "Сохранить",
        // Editor
        "visual" to "Визуально",
        "html_code" to "HTML код",
        "words" to "Слов",
        "chapter_text" to "ТЕКСТ ГЛАВЫ",
        "chapter_html" to "HTML-КОД ГЛАВЫ",
        "chapter_title" to "НАЗВАНИЕ ГЛАВЫ",
        "bold" to "Жирный",
        "italic" to "Курсив",
        "underlined" to "Подчеркнутый",
        "strikethrough" to "Зачеркнутый",
        "paragraph" to "Абзац",
        "stats_words" to "Всего слов",
        "stats_chars" to "Всего символов",
        "preview" to "Предпросмотр"
    )

    private val en = mapOf(
        "app_name" to "EpubEdit",
        "no_titles" to "No titles added",
        "press_add_button" to "Tap the + button to create a new project",
        "new_title" to "New Title",
        "enter_title" to "Enter the book project name or original title:",
        "title_label" to "Title Name",
        "title_placeholder" to "For example: Beyond the Event Horizon",
        "create" to "Create",
        "cancel" to "Cancel",
        "delete_title" to "Delete Title",
        "no_author" to "Author not specified",
        "created_at" to "Created: ",
        "add_title" to "Add title",
        "settings" to "Settings",
        "language" to "Language",
        "english" to "English (Английский)",
        "russian" to "Русский (Russian)",
        "auto_close" to "Auto-close HTML tags",
        "close" to "Close",
        // Details tabs and fields
        "tab_files" to "Files",
        "tab_chapters" to "Chapters",
        "tab_info" to "Info",
        "tab_stats" to "Statistics",
        "back" to "Back",
        "save" to "Save",
        // Editor
        "visual" to "Visual",
        "html_code" to "HTML Code",
        "words" to "Words",
        "chapter_text" to "CHAPTER TEXT",
        "chapter_html" to "CHAPTER HTML",
        "chapter_title" to "CHAPTER TITLE",
        "bold" to "Bold",
        "italic" to "Italic",
        "underlined" to "Underlined",
        "strikethrough" to "Strikethrough",
        "paragraph" to "Paragraph",
        "stats_words" to "Total words",
        "stats_chars" to "Total characters",
        "preview" to "Preview"
    )

    fun t(key: String, lang: String): String {
        val dictionary = if (lang == "en") en else ru
        return dictionary[key] ?: ru[key] ?: key
    }
}
