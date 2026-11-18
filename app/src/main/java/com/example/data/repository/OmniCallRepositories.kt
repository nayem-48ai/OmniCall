package com.example.data.repository

import com.example.data.local.CallLogDao
import com.example.data.local.CallLogEntity
import com.example.data.local.RecordingDao
import com.example.data.local.RecordingEntity
import com.example.data.local.SipAccountDao
import com.example.data.local.SipAccountEntity
import com.example.data.security.KeystoreCredentialVault
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import com.example.domain.model.RecordingFormat
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipBalancePreset
import com.example.domain.model.SipRegistrationStatus
import com.example.domain.model.SipTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

data class CallLogItem(
    val id: Long,
    val remoteHandle: String,
    val displayName: String,
    val direction: CallDirection,
    val sourceType: CallSourceType,
    val lineLabel: String,
    val timestampMs: Long,
    val durationSec: Int,
    val recordingId: Long?
)

data class RecordingItem(
    val id: Long,
    val fileName: String,
    val filePath: String,
    val format: RecordingFormat,
    val sampleRateHz: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val remoteHandle: String,
    val remoteDisplayName: String,
    val sourceLabel: String,
    val createdAtMs: Long
)

class SipAccountRepository(
    private val dao: SipAccountDao,
    private val vault: KeystoreCredentialVault
) {
    val accountsFlow: Flow<List<SipAccountModel>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getAllAccounts(): List<SipAccountModel> = dao.getAll().map { it.toDomain() }

    suspend fun getById(accountId: Long): SipAccountModel? = dao.getById(accountId)?.toDomain()

    suspend fun getDefaultActiveAccount(): SipAccountModel? {
        val all = getAllAccounts()
        return all.firstOrNull { it.isEnabled && it.isDefault }
            ?: all.firstOrNull { it.isEnabled }
    }

    suspend fun saveAccountWithPassword(account: SipAccountModel, password: String): Long {
        val existing = if (account.id > 0L) dao.getById(account.id) else null
        val merged = if (existing != null) {
            account.copy(
                balancePreset = runCatching { SipBalancePreset.valueOf(existing.balancePreset) }.getOrDefault(account.balancePreset),
                balanceCheckUrl = existing.balanceCheckUrl.ifBlank { account.balanceCheckUrl },
                showBalanceInKeypad = existing.showBalanceInKeypad || account.showBalanceInKeypad,
                lastBalance = existing.lastBalance ?: account.lastBalance,
                lastBalanceError = existing.lastBalanceError,
                lastBalanceUpdatedMs = existing.lastBalanceUpdatedMs ?: account.lastBalanceUpdatedMs
            )
        } else account

        val entity = merged.toEntity()
        val id = dao.insert(entity)
        val savedId = if (merged.id > 0L) merged.id else id
        if (merged.isDefault) {
            dao.setDefaultAccount(savedId)
        }
        vault.saveSipPassword(savedId, password)
        return savedId
    }

    suspend fun getPassword(accountId: Long): String? = vault.getSipPassword(accountId)

    suspend fun setDefaultAccount(accountId: Long) = dao.setDefaultAccount(accountId)

    suspend fun setEnabled(account: SipAccountModel, enabled: Boolean) {
        dao.update(account.copy(isEnabled = enabled).toEntity())
    }

    suspend fun updateRegistrationStatus(
        accountId: Long,
        status: SipRegistrationStatus,
        error: String? = null
    ) {
        val registeredAt = if (status == SipRegistrationStatus.REGISTERED) System.currentTimeMillis() else null
        dao.updateRegistrationStatus(accountId, status.name, error, registeredAt)
    }

    suspend fun updateBalanceConfig(
        accountId: Long,
        preset: SipBalancePreset,
        customUrl: String,
        showInKeypad: Boolean
    ) {
        dao.updateBalanceConfig(accountId, preset.name, customUrl.trim(), showInKeypad)
    }

    suspend fun updateBalanceResult(
        accountId: Long,
        balance: String?,
        error: String?,
        updatedMs: Long?
    ) {
        dao.updateBalanceResult(accountId, balance, error, updatedMs)
    }

    suspend fun deleteAccount(accountId: Long) {
        dao.deleteById(accountId)
        vault.deleteSipPassword(accountId)
    }

    private fun SipAccountEntity.toDomain() = SipAccountModel(
        id = id,
        displayName = displayName,
        domain = domain,
        username = username,
        port = port,
        transport = runCatching { SipTransport.valueOf(transport) }.getOrDefault(SipTransport.UDP),
        autoDiscovered = autoDiscovered,
        preferredCodecs = preferredCodecsCsv.split(",").filter { it.isNotBlank() },
        isEnabled = isEnabled,
        isDefault = isDefault,
        status = runCatching { SipRegistrationStatus.valueOf(status) }.getOrDefault(SipRegistrationStatus.UNREGISTERED),
        lastError = lastError,
        lastRegisteredAtMs = lastRegisteredAtMs,
        balancePreset = runCatching { SipBalancePreset.valueOf(balancePreset) }.getOrDefault(SipBalancePreset.NONE),
        balanceCheckUrl = balanceCheckUrl,
        showBalanceInKeypad = showBalanceInKeypad,
        lastBalance = lastBalance,
        lastBalanceError = lastBalanceError,
        lastBalanceUpdatedMs = lastBalanceUpdatedMs
    )

    private fun SipAccountModel.toEntity() = SipAccountEntity(
        id = id,
        displayName = displayName,
        domain = domain,
        username = username,
        port = port,
        transport = transport.name,
        autoDiscovered = autoDiscovered,
        preferredCodecsCsv = preferredCodecs.joinToString(","),
        isEnabled = isEnabled,
        isDefault = isDefault,
        status = status.name,
        lastError = lastError,
        lastRegisteredAtMs = lastRegisteredAtMs,
        balancePreset = balancePreset.name,
        balanceCheckUrl = balanceCheckUrl,
        showBalanceInKeypad = showBalanceInKeypad,
        lastBalance = lastBalance,
        lastBalanceError = lastBalanceError,
        lastBalanceUpdatedMs = lastBalanceUpdatedMs
    )
}

class CallLogRepository(private val dao: CallLogDao) {
    val recentCallsFlow: Flow<List<CallLogItem>> = dao.observeRecent().map { list ->
        list.map {
            CallLogItem(
                id = it.id,
                remoteHandle = it.remoteHandle,
                displayName = it.displayName,
                direction = runCatching { CallDirection.valueOf(it.direction) }.getOrDefault(CallDirection.OUTGOING),
                sourceType = runCatching { CallSourceType.valueOf(it.sourceType) }.getOrDefault(CallSourceType.SIM_1),
                lineLabel = it.lineLabel,
                timestampMs = it.timestampMs,
                durationSec = it.durationSec,
                recordingId = it.recordingId
            )
        }
    }

    suspend fun logCall(
        remoteHandle: String,
        displayName: String,
        direction: CallDirection,
        sourceType: CallSourceType,
        lineLabel: String,
        durationSec: Int,
        recordingId: Long? = null
    ): Long {
        return dao.insert(
            CallLogEntity(
                remoteHandle = remoteHandle,
                displayName = displayName.ifBlank { remoteHandle },
                direction = direction.name,
                sourceType = sourceType.name,
                lineLabel = lineLabel,
                timestampMs = System.currentTimeMillis(),
                durationSec = durationSec,
                recordingId = recordingId
            )
        )
    }

    suspend fun deleteCall(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}

class RecordingRepository(private val dao: RecordingDao) {
    val recordingsFlow: Flow<List<RecordingItem>> = dao.observeAll().map { list ->
        list.map {
            RecordingItem(
                id = it.id,
                fileName = it.fileName,
                filePath = it.filePath,
                format = runCatching { RecordingFormat.valueOf(it.format) }.getOrDefault(RecordingFormat.AAC_M4A),
                sampleRateHz = it.sampleRateHz,
                durationMs = it.durationMs,
                fileSizeBytes = it.fileSizeBytes,
                remoteHandle = it.remoteHandle,
                remoteDisplayName = it.remoteDisplayName,
                sourceLabel = it.sourceLabel,
                createdAtMs = it.createdAtMs
            )
        }
    }

    suspend fun saveRecording(item: RecordingItem): Long {
        return dao.insert(
            RecordingEntity(
                fileName = item.fileName,
                filePath = item.filePath,
                format = item.format.name,
                sampleRateHz = item.sampleRateHz,
                durationMs = item.durationMs,
                fileSizeBytes = item.fileSizeBytes,
                remoteHandle = item.remoteHandle,
                remoteDisplayName = item.remoteDisplayName,
                sourceLabel = item.sourceLabel,
                createdAtMs = item.createdAtMs
            )
        )
    }

    suspend fun deleteRecording(item: RecordingItem) {
        runCatching { File(item.filePath).delete() }
        dao.deleteById(item.id)
    }
}
