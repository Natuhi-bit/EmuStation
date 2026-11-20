package com.emustation.emusync.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromList(v: List<String>): String = Json.encodeToString(v)

    @TypeConverter
    fun toList(v: String): List<String> = Json.decodeFromString(v)
}

@Database(
    entities = [
        TargetEntity::class, BaseStateEntity::class, HashCacheEntity::class,
        PlaySessionEntity::class, ConflictEntity::class, SyncLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targets(): TargetDao
    abstract fun baseState(): BaseStateDao
    abstract fun hashCache(): HashCacheDao
    abstract fun playSessions(): PlaySessionDao
    abstract fun conflicts(): ConflictDao
    abstract fun syncLog(): SyncLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "emusync.db").build()
    }
}
