package com.aistudio.epubedit.kqptxy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BookDao {
    // Titles
    @Query("SELECT * FROM titles ORDER BY createdAt DESC")
    abstract fun getAllTitles(): Flow<List<Title>>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    abstract fun getTitleById(titleId: Long): Flow<Title?>

    @Query("SELECT * FROM titles WHERE id = :titleId")
    abstract suspend fun getTitleByIdOneShot(titleId: Long): Title?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTitle(title: Title): Long

    @Update
    abstract suspend fun updateTitle(title: Title)

    @Delete
    abstract suspend fun deleteTitle(title: Title)

    // Source Files
    @Query("SELECT * FROM source_files WHERE titleId = :titleId ORDER BY orderIndex ASC")
    abstract fun getSourceFilesForTitle(titleId: Long): Flow<List<SourceFile>>

    @Query("SELECT * FROM source_files WHERE titleId = :titleId ORDER BY orderIndex ASC")
    abstract suspend fun getSourceFilesForTitleOneShot(titleId: Long): List<SourceFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSourceFile(sourceFile: SourceFile): Long

    @Update
    abstract suspend fun updateSourceFile(sourceFile: SourceFile)

    @Delete
    abstract suspend fun deleteSourceFile(sourceFile: SourceFile)

    @Query("DELETE FROM source_files WHERE titleId = :titleId")
    abstract suspend fun deleteSourceFilesForTitle(titleId: Long)

    // Chapters
    @Query("SELECT * FROM chapters WHERE titleId = :titleId ORDER BY orderIndex ASC")
    abstract fun getChaptersForTitle(titleId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE titleId = :titleId ORDER BY orderIndex ASC")
    abstract suspend fun getChaptersForTitleOneShot(titleId: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    abstract fun getChapterById(chapterId: Long): Flow<Chapter?>

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    abstract suspend fun getChapterByIdOneShot(chapterId: Long): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChapter(chapter: Chapter): Long

    @Update
    abstract suspend fun updateChapter(chapter: Chapter)

    @Delete
    abstract suspend fun deleteChapter(chapter: Chapter)

    @Delete
    abstract suspend fun deleteChapters(chapters: List<Chapter>)

    @Update
    abstract suspend fun updateChaptersOrder(chapters: List<Chapter>)

    @Update
    abstract suspend fun updateSourceFilesOrder(files: List<SourceFile>)
}
