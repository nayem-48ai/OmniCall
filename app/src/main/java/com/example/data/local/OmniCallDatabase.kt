package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SipAccountEntity::class,
        CallLogEntity::class,
        RecordingEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OmniCallDatabase : RoomDatabase() {
    abstract fun sipAccountDao(): SipAccountDao
    abstract fun callLogDao(): CallLogDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: OmniCallDatabase? = null

        fun getInstance(context: Context): OmniCallDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniCallDatabase::class.java,
                    "omnicall_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
