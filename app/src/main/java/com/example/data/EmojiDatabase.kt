package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.ExtractedEmoji

@Database(entities = [ExtractedEmoji::class], version = 2, exportSchema = false)
abstract class EmojiDatabase : RoomDatabase() {
    abstract fun emojiDao(): EmojiDao

    companion object {
        @Volatile
        private var INSTANCE: EmojiDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE extracted_emojis ADD COLUMN backgroundColor INTEGER")
            }
        }

        fun getDatabase(context: Context): EmojiDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EmojiDatabase::class.java,
                    "emoji_extractor_db"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
