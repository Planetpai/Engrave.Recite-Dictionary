package com.example.vocabwatch.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vocabwatch.data.local.dao.ReviewLogDao
import com.example.vocabwatch.data.local.dao.ReviewStateDao
import com.example.vocabwatch.data.local.dao.WordBookDao
import com.example.vocabwatch.data.local.dao.WordDao
import com.example.vocabwatch.data.local.entity.ReviewLogEntity
import com.example.vocabwatch.data.local.entity.ReviewStateEntity
import com.example.vocabwatch.data.local.entity.WordBookEntity
import com.example.vocabwatch.data.local.entity.WordEntity

@Database(
    entities = [
        WordBookEntity::class,
        WordEntity::class,
        ReviewStateEntity::class,
        ReviewLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordBookDao(): WordBookDao
    abstract fun wordDao(): WordDao
    abstract fun reviewStateDao(): ReviewStateDao
    abstract fun reviewLogDao(): ReviewLogDao

    companion object {
        const val FILE_NAME = "vocab.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                FILE_NAME
            ).build()
    }
}
