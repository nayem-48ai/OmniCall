package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SipAccountEntity::class,
        CallLogEntity::class,
        RecordingEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class OmniCallDatabase : RoomDatabase() {
    abstract fun sipAccountDao(): SipAccountDao
    abstract fun callLogDao(): CallLogDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: OmniCallDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sip_accounts ADD COLUMN balancePreset TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE sip_accounts ADD COLUMN balanceCheckUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sip_accounts ADD COLUMN showBalanceInKeypad INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sip_accounts ADD COLUMN lastBalance TEXT")
                db.execSQL("ALTER TABLE sip_accounts ADD COLUMN lastBalanceError TEXT")
                db.execSQL("ALTER TABLE sip_accounts ADD COLUMN lastBalanceUpdatedMs INTEGER")
            }
        }

        fun getInstance(context: Context): OmniCallDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniCallDatabase::class.java,
                    "omnicall_local.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
