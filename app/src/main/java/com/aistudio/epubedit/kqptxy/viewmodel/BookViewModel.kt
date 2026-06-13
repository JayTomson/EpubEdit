package com.aistudio.epubedit.kqptxy.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aistudio.epubedit.kqptxy.data.*
import com.aistudio.epubedit.kqptxy.util.BookConverter
import com.aistudio.epubedit.kqptxy.util.EpubProcessor
import com.aistudio.epubedit.kqptxy.util.ParsedChapter
import com.aistudio.epubedit.kqptxy.util.WordStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class BookViewModel(private val app: Application, private val repository: BookRepository) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _currentLanguage = MutableStateFlow(prefs.getString("pref_language", "ru") ?: "ru")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _htmlAutoCloseEnabled = MutableStateFlow(prefs.getBoolean("pref_html_autoclose", false))
    val htmlAutoCloseEnabled: StateFlow<Boolean> = _htmlAutoCloseEnabled.asStateFlow()

    private val _reorderingEnabled = MutableStateFlow(prefs.getBoolean("pref_reordering", true))
    val reorderingEnabled: StateFlow<Boolean> = _reorderingEnabled.asStateFlow()

    fun updateLanguage(lang: String) {
        prefs.edit().putString("pref_language", lang).apply()
        _currentLanguage.value = lang
    }

    fun updateHtmlAutoClose(enabled: Boolean) {
        prefs.edit().putBoolean("pref_html_autoclose", enabled).apply()
        _htmlAutoCloseEnabled.value = enabled
    }

    fun updateReordering(enabled: Boolean) {
        prefs.edit().putBoolean("pref_reordering", enabled).apply()
        _reorderingEnabled.value = enabled
    }

    val titles: StateFlow<List<Title>> = repository.allTitles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedTitleId = MutableStateFlow<Long?>(null)
    val selectedTitleId: StateFlow<Long?> = _selectedTitleId.asStateFlow()

    val selectedTitle: StateFlow<Title?> = _selectedTitleId
        .flatMapLatest { id ->
            if (id != null) repository.getTitleById(id) else flowOf(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val sourceFiles: StateFlow<List<SourceFile>> = _selectedTitleId
        .flatMapLatest { id ->
            if (id != null) repository.getSourceFilesForTitle(id) else flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    val chapters: StateFlow<List<Chapter>> = _selectedTitleId
        .flatMapLatest { id ->
            if (id != null) repository.getChaptersForTitle(id) else flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    private val _editingChapterId = MutableStateFlow<Long?>(null)
    val editingChapterId: StateFlow<Long?> = _editingChapterId.asStateFlow()

    val editingChapter: StateFlow<Chapter?> = _editingChapterId
        .flatMapLatest { id ->
            if (id != null) repository.getChapterById(id) else flowOf(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun selectTitle(titleId: Long?) {
        _selectedTitleId.value = titleId
    }

    fun selectEditingChapter(chapterId: Long?) {
        _editingChapterId.value = chapterId
    }

    fun addTitle(name: String) {
        viewModelScope.launch {
            val title = Title(
                name = name,
                author = "",
                description = "",
                outputFileName = "${name.replace(" ", "_")}_final.epub"
            )
            repository.insertTitle(title)
        }
    }

    fun deleteTitle(title: Title) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val mediaDir = File(app.filesDir, "epub_media")
                    mediaDir.listFiles { _, name -> name.startsWith("book_${title.id}_") }
                        ?.forEach { it.delete() }
                        
                    // Clean up custom cover file if it exists and lies in filesDir
                    title.coverImage?.let { path ->
                        val coverFile = File(path)
                        if (coverFile.exists() && coverFile.parentFile?.absolutePath == app.filesDir.absolutePath) {
                            coverFile.delete()
                        }
                    }

                    // Scan chapter content for explicit media insertion files
                    val chapters = repository.getChaptersForTitleOneShot(title.id)
                    val imgRegex = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    chapters.forEach { chapter ->
                        imgRegex.findAll(chapter.contentHtml).forEach { match ->
                            val src = match.groupValues[1]
                            val fileName = File(src).name
                            val target = File(mediaDir, fileName)
                            if (target.exists() && (fileName.startsWith("media_") || fileName.startsWith("book_${title.id}_"))) {
                                target.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            repository.deleteTitle(title)
            if (_selectedTitleId.value == title.id) {
                _selectedTitleId.value = null
            }
        }
    }

    fun updateTitleInfo(
        titleId: Long,
        name: String,
        author: String,
        description: String,
        coverImage: String?,
        outputFileName: String
    ) {
        viewModelScope.launch {
            val existing = repository.getTitleByIdOneShot(titleId) ?: return@launch
            val updated = existing.copy(
                name = name,
                author = author,
                description = description,
                coverImage = coverImage,
                outputFileName = outputFileName
            )
            repository.updateTitle(updated)
        }
    }

    fun importEpub(context: Context, titleId: Long, uri: Uri, fileName: String, fileSize: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parsed = EpubProcessor.parseEpub(context, uri, titleId) ?: return@launch
                
                // Add SourceFile record
                val nextFileIndex = repository.getSourceFilesForTitleOneShot(titleId).size
                val sfId = repository.insertSourceFile(
                    SourceFile(
                        titleId = titleId,
                        fileName = fileName,
                        fileSize = fileSize,
                        orderIndex = nextFileIndex,
                        uploadedAt = System.currentTimeMillis()
                    )
                )

                // If cover image was extracted and the book doesn't have a cover yet, update it
                val currentTitle = repository.getTitleByIdOneShot(titleId)
                if (currentTitle != null && currentTitle.coverImage.isNullOrEmpty() && parsed.coverImagePath != null) {
                    val updatedTitle = currentTitle.copy(coverImage = parsed.coverImagePath)
                    repository.updateTitle(updatedTitle)
                }

                 // Append parsed chapters to Title's chapters list
                 val nextChapterIndex = repository.getChaptersForTitleOneShot(titleId).size
                 parsed.chapters.forEachIndexed { i, pc ->
                     repository.insertChapter(
                         Chapter(
                             titleId = titleId,
                             sourceFileId = sfId,
                             title = pc.title,
                             contentHtml = pc.contentHtml,
                             orderIndex = nextChapterIndex + i,
                             wordCount = pc.wordCount,
                             characterCount = pc.characterCount,
                             previewImagePath = pc.previewImagePath
                         )
                     )
                 }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed importing epub", e)
            }
        }
    }

    fun convertAndImportFile(context: Context, titleId: Long, uri: Uri, fileName: String, fileSize: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ext = fileName.substringAfterLast(".", "").lowercase()
                val tempEpubFile = File(context.cacheDir, "${java.util.UUID.randomUUID()}_converted.epub")
                
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                var success = false
                
                if (ext == "fb2") {
                    success = BookConverter.convertFb2ToEpub(context, inputStream, tempEpubFile)
                }
                inputStream.close()

                if (success && tempEpubFile.exists()) {
                    val convertedName = fileName.replace(Regex("\\.fb2$", RegexOption.IGNORE_CASE), "") + " (Converted).epub"
                    val fileLength = tempEpubFile.length()
                    val convertedUri = Uri.fromFile(tempEpubFile)
                    
                    val parsed = EpubProcessor.parseEpub(context, convertedUri, titleId)
                    if (parsed != null) {
                        val nextFileIndex = repository.getSourceFilesForTitleOneShot(titleId).size
                        val sfId = repository.insertSourceFile(
                            SourceFile(
                                titleId = titleId,
                                fileName = convertedName,
                                fileSize = fileLength,
                                orderIndex = nextFileIndex,
                                uploadedAt = System.currentTimeMillis()
                            )
                        )

                        val currentTitle = repository.getTitleByIdOneShot(titleId)
                        if (currentTitle != null && currentTitle.coverImage.isNullOrEmpty() && parsed.coverImagePath != null) {
                            val updatedTitle = currentTitle.copy(coverImage = parsed.coverImagePath)
                            repository.updateTitle(updatedTitle)
                        }

                        val nextChapterIndex = repository.getChaptersForTitleOneShot(titleId).size
                        parsed.chapters.forEachIndexed { i, pc ->
                            repository.insertChapter(
                                Chapter(
                                    titleId = titleId,
                                    sourceFileId = sfId,
                                    title = pc.title,
                                    contentHtml = pc.contentHtml,
                                    orderIndex = nextChapterIndex + i,
                                    wordCount = pc.wordCount,
                                    characterCount = pc.characterCount,
                                    previewImagePath = pc.previewImagePath
                                )
                            )
                        }
                    }
                    try { tempEpubFile.delete() } catch (ignored: Exception) {}
                } else {
                    Log.e("BookViewModel", "Failed converting file $fileName to EPUB")
                }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed convert and import file process", e)
            }
        }
    }

    fun renameSourceFile(file: SourceFile, newName: String) {
        viewModelScope.launch {
            repository.updateSourceFile(file.copy(fileName = newName))
        }
    }

    fun deleteSourceFile(file: SourceFile) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete the source file record
            repository.deleteSourceFile(file)
            
            val titleId = file.titleId
            // Get all chapters for this title and delete those belonging to this source file
            val allChs = repository.getChaptersForTitleOneShot(titleId)
            val toDelete = allChs.filter { it.sourceFileId == file.id }
            if (toDelete.isNotEmpty()) {
                repository.deleteChapters(toDelete)
            }
            
            // Re-index remaining chapters to keep orderIndex contiguous and properly sorted
            val remainingChs = allChs.filter { it.sourceFileId != file.id }
                .mapIndexed { idx, ch -> ch.copy(orderIndex = idx) }
            repository.updateChaptersOrder(remainingChs)

            // Also re-index remaining source files so their index is contiguous
            val remainingFiles = repository.getSourceFilesForTitleOneShot(titleId)
            val updatedFiles = remainingFiles.mapIndexed { idx, sf -> sf.copy(orderIndex = idx) }
            repository.updateSourceFilesOrder(updatedFiles)
        }
    }

    fun addManualChapter(titleId: Long, title: String) {
        viewModelScope.launch {
            val chs = repository.getChaptersForTitleOneShot(titleId)
            val newIdx = chs.size
            repository.insertChapter(
                Chapter(
                    titleId = titleId,
                    title = title,
                    contentHtml = "<p></p>",
                    orderIndex = newIdx,
                    wordCount = 0,
                    characterCount = 0
                )
            )
        }
    }

    fun updateChapterContent(chapterId: Long, title: String, contentHtml: String, previewImagePath: String?) {
        viewModelScope.launch {
            val current = repository.getChapterByIdOneShot(chapterId) ?: return@launch
            val words = WordStatsHelper.countWords(contentHtml)
            val chars = WordStatsHelper.countCharacters(contentHtml)
            
            val updated = current.copy(
                title = title.ifBlank { "Без названия" },
                contentHtml = contentHtml,
                previewImagePath = previewImagePath,
                wordCount = words,
                characterCount = chars
            )
            repository.updateChapter(updated)
        }
    }

    fun deleteSelectedChapters(chapterIds: List<Long>) {
        viewModelScope.launch {
            val idSet = chapterIds.toSet()
            val allChs = repository.getChaptersForTitleOneShot(_selectedTitleId.value ?: return@launch)
            val toDelete = allChs.filter { it.id in idSet }
            repository.deleteChapters(toDelete)
            
            withContext(Dispatchers.IO) {
                try {
                    val mediaDir = File(app.filesDir, "epub_media")
                    val imgRegex = Regex("<img[^>]+src=[\"'](media_[a-zA-Z0-9_.]+)[\"']", RegexOption.IGNORE_CASE)
                    toDelete.forEach { chapter ->
                        imgRegex.findAll(chapter.contentHtml).forEach { match ->
                            val fileName = match.groupValues[1]
                            val target = File(mediaDir, fileName)
                            if (target.exists()) target.delete()
                        }
                    }
                } catch (e: Exception) {}
            }
            
            // Re-index remaining chapters to keep orderIndex contiguous
            val remaining = allChs.filter { it.id !in idSet }
                .mapIndexed { idx, ch -> ch.copy(orderIndex = idx) }
            repository.updateChaptersOrder(remaining)
        }
    }

    fun keepSelectedChapters(chapterIds: List<Long>) {
        viewModelScope.launch {
            val keepSet = chapterIds.toSet()
            val allChs = repository.getChaptersForTitleOneShot(_selectedTitleId.value ?: return@launch)
            val toDelete = allChs.filter { it.id !in keepSet }
            repository.deleteChapters(toDelete)

            // Re-index remaining chapters to keep orderIndex contiguous
            val remaining = allChs.filter { it.id in keepSet }
                .mapIndexed { idx, ch -> ch.copy(orderIndex = idx) }
            repository.updateChaptersOrder(remaining)
        }
    }

    fun reorderChapters(chaptersList: List<Chapter>) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = chaptersList.mapIndexed { i, ch -> ch.copy(orderIndex = i) }
            repository.updateChaptersOrder(updatedList)
        }
    }

    fun reorderSourceFiles(filesList: List<SourceFile>) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = filesList.mapIndexed { i, f -> f.copy(orderIndex = i) }
            repository.updateSourceFilesOrder(updatedList)

            // Auto-reorder the chapters belonging to these source files to match the new file order
            val titleId = filesList.firstOrNull()?.titleId ?: return@launch
            val allChs = repository.getChaptersForTitleOneShot(titleId)
            val fileOrderMap = filesList.mapIndexed { index, sf -> sf.id to index }.toMap()

            val orderedChapters = allChs.sortedWith(
                compareBy<Chapter> { ch ->
                    val fileIndex = ch.sourceFileId?.let { fileOrderMap[it] }
                    fileIndex ?: Int.MAX_VALUE // Put chapters without a file (e.g. manually added ones) at the end
                }.thenBy { ch ->
                    ch.orderIndex // Preserve original relative order of chapters within each file/section
                }
            )

            val updatedChaptersList = orderedChapters.mapIndexed { index, ch ->
                ch.copy(orderIndex = index)
            }
            repository.updateChaptersOrder(updatedChaptersList)
        }
    }

    fun exportMergedEpub(context: Context, titleId: Long, generateToc: Boolean = true, onFinished: (File?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val title = repository.getTitleByIdOneShot(titleId) ?: return@launch
                val allChs = repository.getChaptersForTitleOneShot(titleId)
                val sourceFiles = repository.getSourceFilesForTitleOneShot(titleId).sortedBy { it.orderIndex }
                
                val sortedChs = allChs.sortedBy { it.orderIndex }
                
                val plist = sortedChs.map {
                    ParsedChapter(
                        title = it.title,
                        contentHtml = it.contentHtml,
                        wordCount = it.wordCount,
                        characterCount = it.characterCount,
                        previewImagePath = it.previewImagePath
                    )
                }

                val result = EpubProcessor.exportToEpub(
                    context = context,
                    fileName = title.outputFileName ?: "${title.name}.epub",
                    title = title.name,
                    author = title.author ?: "Автор",
                    description = title.description ?: "",
                    coverImagePath = title.coverImage,
                    chapters = plist,
                    titleId = titleId,
                    generateToc = generateToc
                )
                withContext(Dispatchers.Main) {
                    onFinished(result)
                }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Merge export failed", e)
                withContext(Dispatchers.Main) {
                    onFinished(null)
                }
            }
        }
    }

    /**
     * Copy Cover Image URI from user's photo picker to app's cache directory
     * and returns the local cache path.
     */
    fun saveCoverImageLocally(context: Context, localUri: Uri): String? {
        val destFile = File(context.filesDir, "epub_user_cover_${System.currentTimeMillis()}.jpg")
        return try {
            val ips = context.contentResolver.openInputStream(localUri) ?: return null
            val ops = FileOutputStream(destFile)
            ips.use { input ->
                ops.use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e("BookViewModel", "Failed caching cover", e)
            null
        }
    }
}

class BookViewModelFactory(private val application: Application, private val repository: BookRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BookViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BookViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
