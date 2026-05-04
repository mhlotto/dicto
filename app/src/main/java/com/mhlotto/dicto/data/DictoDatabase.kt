package com.mhlotto.dicto.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, NoteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DictoDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var instance: DictoDatabase? = null

        fun get(context: Context): DictoDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DictoDatabase::class.java,
                    "dicto.db",
                ).build().also { instance = it }
            }
        }
    }
}
