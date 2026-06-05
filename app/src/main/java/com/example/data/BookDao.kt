package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    // Titles
    @Query("SELECT * FROM titles ORDER BY createdAt DESC")
    fun getAllTitles(): Flow<List<Title>>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    fun getTitleById(titleId: Long): Flow<Title?>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    suspend fun getTitleByIdOneShot(titleId: Long): Title?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTitle(title: Title): Long

    @Update
    suspend fun updateTitle(title: Title)

    @Delete
    suspend fun deleteTitle(title: Title)

    // Source Files
    @Query("SELECT * FROM source_files WHERE titleId = :titleId ORDER BY orderIndex ASC")
    fun getSourceFilesForTitle(titleId: Long): Flow<List<SourceFile>>

    @Query("SELECT * FROM source_files WHERE titleId = :titleId ORDER BY orderIndex ASC")
    suspend fun getSourceFilesForTitleOneShot(titleId: Long): List<SourceFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSourceFile(sourceFile: SourceFile): Long

    @Update
    suspend fun updateSourceFile(sourceFile: SourceFile)

    @Delete
    suspend fun deleteSourceFile(sourceFile: SourceFile)

    @Query("DELETE FROM source_files WHERE titleId = :titleId")
    suspend fun deleteSourceFilesForTitle(titleId: Long)

    // Chapters
    @Query("SELECT * FROM chapters WHERE titleId = :titleId ORDER BY orderIndex ASC")
    fun getChaptersForTitle(titleId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE titleId = :titleId ORDER BY orderIndex ASC")
    suspend fun getChaptersForTitleOneShot(titleId: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    fun getChapterById(chapterId: Long): Flow<Chapter?>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getChapterByIdOneShot(chapterId: Long): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: Chapter): Long

    @Update
    suspend fun updateChapter(chapter: Chapter)

    @Delete
    suspend fun deleteChapter(chapter: Chapter)

    @Transaction
    suspend fun deleteChapters(chapters: List<Chapter>) {
        chapters.forEach { deleteChapter(it) }
    }

    @Transaction
    suspend fun updateChaptersOrder(chapters: List<Chapter>) {
        chapters.forEach { updateChapter(it) }
    }

    @Transaction
    suspend fun updateSourceFilesOrder(files: List<SourceFile>) {
        files.forEach { updateSourceFile(it) }
    }
}
