package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedLinkDao {
    @Query("SELECT * FROM generated_links ORDER BY createdAt DESC")
    fun getAllLinks(): Flow<List<GeneratedLink>>

    @Query("SELECT * FROM generated_links WHERE isApk = 1 ORDER BY createdAt DESC")
    fun getApkLinks(): Flow<List<GeneratedLink>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: GeneratedLink): Long

    @Update
    suspend fun updateLink(link: GeneratedLink)

    @Delete
    suspend fun deleteLink(link: GeneratedLink)

    @Query("DELETE FROM generated_links WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM generated_links")
    suspend fun deleteAll()

    @Query("UPDATE generated_links SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)
}
