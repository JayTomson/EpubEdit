package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "titles")
data class Title(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val author: String? = "",
    val description: String? = "",
    val coverImage: String? = null,
    val outputFileName: String? = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "source_files",
    foreignKeys = [
        ForeignKey(
            entity = Title::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["titleId"])]
)
data class SourceFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleId: Long,
    val fileName: String,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val wordCount: Int = 0,
    val characterCount: Int = 0,
    val orderIndex: Int = 0,
    val uploadedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Title::class,
            parentColumns = ["id"],
            childColumns = ["titleId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SourceFile::class,
            parentColumns = ["id"],
            childColumns = ["sourceFileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["titleId"]),
        Index(value = ["sourceFileId"])
    ]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleId: Long,
    val sourceFileId: Long? = null,
    val title: String,
    val contentHtml: String,
    val orderIndex: Int = 0,
    val wordCount: Int = 0,
    val characterCount: Int = 0,
    val previewImagePath: String? = null
)
