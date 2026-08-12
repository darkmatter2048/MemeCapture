package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.ExtractedEmoji

@Database(entities = [ExtractedEmoji::class], version = 1, exportSchema = false)
abstract class EmojiDatabase : RoomDatabase() {
    abstract fun emojiDao(): EmojiDao

    companion object {
        @Volatile
        private var INSTANCE: EmojiDatabase? = null

        fun getDatabase(context: Context): EmojiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EmojiDatabase::class.java,
                    "emoji_extractor_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
