package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sip_accounts")
data class SipAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val displayName: String,
    val domain: String,
    val username: String,
    val port: Int,
    val transport: String,
    val autoDiscovered: Boolean,
    val preferredCodecsCsv: String,
    val isEnabled: Boolean,
    val isDefault: Boolean,
    val status: String,
    val lastError: String?,
    val lastRegisteredAtMs: Long?,
    val balancePreset: String = "NONE",
    val balanceCheckUrl: String = "",
    val showBalanceInKeypad: Boolean = false,
    val lastBalance: String? = null,
    val lastBalanceError: String? = null,
    val lastBalanceUpdatedMs: Long? = null
)

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val remoteHandle: String,
    val displayName: String,
    val direction: String,
    val sourceType: String,
    val lineLabel: String,
    val timestampMs: Long,
    val durationSec: Int,
    val recordingId: Long? = null
)

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileName: String,
    val filePath: String,
    val format: String,
    val sampleRateHz: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val remoteHandle: String,
    val remoteDisplayName: String,
    val sourceLabel: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "blocked_numbers",
    indices = [Index(value = ["normalizedKey"], unique = true)]
)
data class BlockedNumberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val rawNumber: String,
    val normalizedKey: String,
    val displayName: String,
    val createdAtMs: Long
)
