package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.ExtractedEmoji
import kotlinx.coroutines.flow.Flow

@Dao
interface EmojiDao {
    @Query("SELECT * FROM extracted_emojis ORDER BY timestamp DESC")
    fun getAllEmojis(): Flow<List<ExtractedEmoji>>

    @Query("SELECT * FROM extracted_emojis WHERE isAnimated = :isAnimated ORDER BY timestamp DESC")
    fun getEmojisByType(isAnimated: Boolean): Flow<List<ExtractedEmoji>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmoji(emoji: ExtractedEmoji)

    @Delete
    suspend fun deleteEmoji(emoji: ExtractedEmoji)

    @Query("DELETE FROM extracted_emojis WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM extracted_emojis")
    suspend fun clearAll()
}
