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
        RecordingEntity::class,
        BlockedNumberEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OmniCallDatabase : RoomDatabase() {
    abstract fun sipAccountDao(): SipAccountDao
    abstract fun callLogDao(): CallLogDao
    abstract fun recordingDao(): RecordingDao
    abstract fun blocklistDao(): BlocklistDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blocked_numbers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `rawNumber` TEXT NOT NULL, `normalizedKey` TEXT NOT NULL, `displayName` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_blocked_numbers_normalizedKey` ON `blocked_numbers` (`normalizedKey`)"
                )
            }
        }

        fun getInstance(context: Context): OmniCallDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniCallDatabase::class.java,
                    "omnicall_local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
