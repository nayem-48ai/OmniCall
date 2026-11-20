package com.example

import android.app.Application
import com.example.audio.DualStreamCallRecorder
import com.example.audio.RtpAudioEngine
import com.example.data.local.OmniCallDatabase
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.BlocklistRepository
import com.example.data.repository.CallLogRepository
import com.example.data.repository.ContactsRepository
import com.example.data.repository.RecordingRepository
import com.example.data.repository.SipAccountRepository
import com.example.data.security.KeystoreCredentialVault
import com.example.domain.ai.CallEventBus
import com.example.domain.ai.DefaultCallEventBus
import com.example.domain.model.SipBalancePreset
import com.example.sip.DnsSrvResolver
import com.example.sip.SipAutoConfigEngine
import com.example.sip.SipBalanceFetcher
import com.example.sip.SipClientEngine
import com.example.telecom.ActiveCallCoordinator
import com.example.telecom.CallNotificationManager
import com.example.telecom.ProximitySensorWakeLockManager
import com.example.telecom.SimSubscriptionDetector
import com.example.telecom.TelecomCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Clean Constructor-Injected App Container keeping OmniCall modular and testable without heavy DI boilerplate.
 */
class AppContainer(application: Application) {
    val database: OmniCallDatabase by lazy { OmniCallDatabase.getInstance(application) }
    val credentialVault: KeystoreCredentialVault by lazy { KeystoreCredentialVault(application) }
    val preferencesRepository: AppPreferencesRepository by lazy { AppPreferencesRepository(application) }

    val sipAccountRepository: SipAccountRepository by lazy {
        SipAccountRepository(database.sipAccountDao(), credentialVault)
    }
    val callLogRepository: CallLogRepository by lazy { CallLogRepository(database.callLogDao()) }
    val recordingRepository: RecordingRepository by lazy { RecordingRepository(database.recordingDao()) }
    val blocklistRepository: BlocklistRepository by lazy {
        BlocklistRepository(application, database.blocklistDao())
    }
    val contactsRepository: ContactsRepository by lazy { ContactsRepository(application) }

    val eventBus: CallEventBus = DefaultCallEventBus()

    val dnsSrvResolver: DnsSrvResolver by lazy { DnsSrvResolver(application) }
    val sipAutoConfigEngine: SipAutoConfigEngine by lazy { SipAutoConfigEngine(dnsSrvResolver) }
    val sipClientEngine: SipClientEngine by lazy {
        SipClientEngine(application, sipAccountRepository, eventBus)
    }
    val sipBalanceFetcher: SipBalanceFetcher by lazy { SipBalanceFetcher(sipAccountRepository) }

    val rtpAudioEngine: RtpAudioEngine by lazy { RtpAudioEngine(application) }
    val dualStreamCallRecorder: DualStreamCallRecorder by lazy {
        DualStreamCallRecorder(
            context = application,
            audioStreamTap = rtpAudioEngine,
            recordingRepository = recordingRepository,
            eventBus = eventBus
        )
    }

    val simSubscriptionDetector: SimSubscriptionDetector by lazy { SimSubscriptionDetector(application) }
    val telecomCallManager: TelecomCallManager by lazy { TelecomCallManager(application, simSubscriptionDetector) }
    val callNotificationManager: CallNotificationManager by lazy { CallNotificationManager(application) }
    val proximityWakeLockManager: ProximitySensorWakeLockManager by lazy {
        ProximitySensorWakeLockManager(application)
    }

    val activeCallCoordinator: ActiveCallCoordinator by lazy {
        ActiveCallCoordinator(
            context = application,
            telecomCallManager = telecomCallManager,
            simDetector = simSubscriptionDetector,
            contactsRepository = contactsRepository,
            blocklistRepository = blocklistRepository,
            sipClientEngine = sipClientEngine,
            rtpAudioEngine = rtpAudioEngine,
            callRecorder = dualStreamCallRecorder,
            callLogRepository = callLogRepository,
            prefsRepository = preferencesRepository,
            notificationManager = callNotificationManager,
            proximityWakeLockManager = proximityWakeLockManager,
            eventBus = eventBus
        )
    }
}

class OmniCallApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Pre-warm channels, SIM cache, blocklist cache, and SIP listener off the main thread
        // so both cold-start UI launch and background incoming SIM/SIP calls respond instantly.
        appScope.launch {
            runCatching { container.blocklistRepository.warmupCache() }
            runCatching { container.callNotificationManager.ensureChannels() }
            runCatching { container.simSubscriptionDetector.detectActiveSims() }
            runCatching { container.telecomCallManager.registerSelfManagedSipAccount() }

            val defaultSip = runCatching { container.sipAccountRepository.getDefaultActiveAccount() }.getOrNull()
            if (defaultSip != null) {
                container.sipClientEngine.startAccountRegistration(defaultSip)
                if (defaultSip.balancePreset != SipBalancePreset.NONE && defaultSip.showBalanceInKeypad) {
                    container.sipBalanceFetcher.fetchBalance(defaultSip)
                }
            }
        }
    }
}
