package com.aistudio.epubedit.kqptxy.data

import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {
    val allTitles: Flow<List<Title>> = bookDao.getAllTitles()

    fun getTitleById(titleId: Long): Flow<Title?> = bookDao.getTitleById(titleId)
    suspend fun getTitleByIdOneShot(titleId: Long): Title? = bookDao.getTitleByIdOneShot(titleId)

    suspend fun insertTitle(title: Title): Long = bookDao.insertTitle(title)
    suspend fun updateTitle(title: Title) = bookDao.updateTitle(title)
    suspend fun deleteTitle(title: Title) = bookDao.deleteTitle(title)

    fun getSourceFilesForTitle(titleId: Long): Flow<List<SourceFile>> =
        bookDao.getSourceFilesForTitle(titleId)

    suspend fun getSourceFilesForTitleOneShot(titleId: Long): List<SourceFile> =
        bookDao.getSourceFilesForTitleOneShot(titleId)

    suspend fun insertSourceFile(sourceFile: SourceFile): Long =
        bookDao.insertSourceFile(sourceFile)

    suspend fun updateSourceFile(sourceFile: SourceFile) = bookDao.updateSourceFile(sourceFile)
    suspend fun deleteSourceFile(sourceFile: SourceFile) = bookDao.deleteSourceFile(sourceFile)

    fun getChaptersForTitle(titleId: Long): Flow<List<Chapter>> =
        bookDao.getChaptersForTitle(titleId)

    suspend fun getChaptersForTitleOneShot(titleId: Long): List<Chapter> =
        bookDao.getChaptersForTitleOneShot(titleId)

    fun getChapterById(chapterId: Long): Flow<Chapter?> = bookDao.getChapterById(chapterId)
    suspend fun getChapterByIdOneShot(chapterId: Long): Chapter? =
        bookDao.getChapterByIdOneShot(chapterId)

    suspend fun insertChapter(chapter: Chapter): Long = bookDao.insertChapter(chapter)
    suspend fun updateChapter(chapter: Chapter) = bookDao.updateChapter(chapter)
    suspend fun deleteChapter(chapter: Chapter) = bookDao.deleteChapter(chapter)

    suspend fun deleteChapters(chapters: List<Chapter>) = bookDao.deleteChapters(chapters)
    suspend fun updateChaptersOrder(chapters: List<Chapter>) = bookDao.updateChaptersOrder(chapters)
    suspend fun updateSourceFilesOrder(files: List<SourceFile>) =
        bookDao.updateSourceFilesOrder(files)
        
    suspend fun appendSourceFileAtomically(sourceFile: SourceFile): Long =
        bookDao.appendSourceFileAtomically(sourceFile)
        
    suspend fun appendChaptersAtomically(chapters: List<Chapter>) =
        bookDao.appendChaptersAtomically(chapters)
}
