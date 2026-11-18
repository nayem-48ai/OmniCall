package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SipAccountDao {
    @Query("SELECT * FROM sip_accounts ORDER BY isDefault DESC, id ASC")
    fun observeAll(): Flow<List<SipAccountEntity>>

    @Query("SELECT * FROM sip_accounts ORDER BY isDefault DESC, id ASC")
    suspend fun getAll(): List<SipAccountEntity>

    @Query("SELECT * FROM sip_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SipAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SipAccountEntity): Long

    @Update
    suspend fun update(entity: SipAccountEntity)

    @Query("UPDATE sip_accounts SET isDefault = CASE WHEN id = :defaultId THEN 1 ELSE 0 END")
    suspend fun setDefaultAccount(defaultId: Long)

    @Query("UPDATE sip_accounts SET status = :status, lastError = :lastError, lastRegisteredAtMs = :registeredAtMs WHERE id = :id")
    suspend fun updateRegistrationStatus(id: Long, status: String, lastError: String?, registeredAtMs: Long?)

    @Query("UPDATE sip_accounts SET balancePreset = :preset, balanceCheckUrl = :url, showBalanceInKeypad = :showInKeypad WHERE id = :id")
    suspend fun updateBalanceConfig(id: Long, preset: String, url: String, showInKeypad: Boolean)

    @Query("UPDATE sip_accounts SET lastBalance = :balance, lastBalanceError = :error, lastBalanceUpdatedMs = :updatedMs WHERE id = :id")
    suspend fun updateBalanceResult(id: Long, balance: String?, error: String?, updatedMs: Long?)

    @Query("DELETE FROM sip_accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestampMs DESC LIMIT 300")
    fun observeRecent(): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CallLogEntity): Long

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_logs")
    suspend fun clearAll()
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecordingEntity): Long

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
