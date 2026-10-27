package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generated_links")
data class GeneratedLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isApk: Boolean,
    val appName: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val downloadUrl: String,
    val providerName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiryInfo: String? = null,
    val customDomain: String? = null,
    val isFavorite: Boolean = false
)
