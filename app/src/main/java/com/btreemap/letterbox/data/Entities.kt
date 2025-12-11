package com.btreemap.letterbox.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a blob stored in the Content-Addressable Storage (CAS).
 * The hash (SHA-256) serves as both the primary key and the filename.
 */
@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey
    @ColumnInfo(name = "hash")
    val hash: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "ref_count")
    val refCount: Int
)

/**
 * Represents a history item linking a user-facing name to a blob.
 * Multiple history items can reference the same blob (deduplication).
 */
@Entity(
    tableName = "history_items",
    foreignKeys = [
        ForeignKey(
            entity = BlobEntity::class,
            parentColumns = ["hash"],
            childColumns = ["blob_hash"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("blob_hash")]
)
data class HistoryItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "blob_hash")
    val blobHash: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "original_uri")
    val originalUri: String?,

    @ColumnInfo(name = "last_accessed")
    val lastAccessed: Long
)
