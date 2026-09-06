package com.example.vocabwatch.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vocabwatch.data.local.dao.MistakeDao
import com.example.vocabwatch.data.local.dao.ReviewLogDao
import com.example.vocabwatch.data.local.dao.ReviewStateDao
import com.example.vocabwatch.data.local.dao.SearchHistoryDao
import com.example.vocabwatch.data.local.dao.WordBookDao
import com.example.vocabwatch.data.local.dao.WordDao
import com.example.vocabwatch.data.local.entity.MistakeEntity
import com.example.vocabwatch.data.local.entity.ReviewLogEntity
import com.example.vocabwatch.data.local.entity.ReviewStateEntity
import com.example.vocabwatch.data.local.entity.SearchHistoryEntity
import com.example.vocabwatch.data.local.entity.WordBookEntity
import com.example.vocabwatch.data.local.entity.WordEntity

@Database(
    entities = [
        WordBookEntity::class,
        WordEntity::class,
        ReviewStateEntity::class,
        ReviewLogEntity::class,
        SearchHistoryEntity::class,
        MistakeEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordBookDao(): WordBookDao
    abstract fun wordDao(): WordDao
    abstract fun reviewStateDao(): ReviewStateDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun mistakeDao(): MistakeDao

    companion object {
        const val FILE_NAME = "vocab.db"

        /** v2：新增搜索历史和错题集两张独立小表，老表不动。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` " +
                        "(`term` TEXT NOT NULL, `phonetic` TEXT, `meaning` TEXT, " +
                        "`lookedUpAt` INTEGER NOT NULL, PRIMARY KEY(`term`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mistake_words` " +
                        "(`term` TEXT NOT NULL, `meaning` TEXT, `phonetic` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`term`))"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                FILE_NAME
            ).addMigrations(MIGRATION_1_2).build()
    }
}

