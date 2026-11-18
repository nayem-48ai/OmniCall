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
    val database = OmniCallDatabase.getInstance(application)
    val credentialVault = KeystoreCredentialVault(application)
    val preferencesRepository = AppPreferencesRepository(application)

    val sipAccountRepository = SipAccountRepository(database.sipAccountDao(), credentialVault)
    val callLogRepository = CallLogRepository(database.callLogDao())
    val recordingRepository = RecordingRepository(database.recordingDao())
    val blocklistRepository = BlocklistRepository(application, database.blocklistDao())
    val contactsRepository = ContactsRepository(application)

    val eventBus: CallEventBus = DefaultCallEventBus()

    val dnsSrvResolver = DnsSrvResolver(application)
    val sipAutoConfigEngine = SipAutoConfigEngine(dnsSrvResolver)
    val sipClientEngine = SipClientEngine(sipAccountRepository, eventBus)
    val sipBalanceFetcher = SipBalanceFetcher(sipAccountRepository)

    val rtpAudioEngine = RtpAudioEngine(application)
    val dualStreamCallRecorder = DualStreamCallRecorder(
        context = application,
        audioStreamTap = rtpAudioEngine,
        recordingRepository = recordingRepository,
        eventBus = eventBus
    )

    val simSubscriptionDetector = SimSubscriptionDetector(application)
    val telecomCallManager = TelecomCallManager(application, simSubscriptionDetector)
    val callNotificationManager = CallNotificationManager(application)
    val proximityWakeLockManager = ProximitySensorWakeLockManager(application)

    val activeCallCoordinator = ActiveCallCoordinator(
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

class OmniCallApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.callNotificationManager.ensureChannels()
        container.telecomCallManager.registerSelfManagedSipAccount()

        // Automatically reconnect default enabled SIP account and refresh Balance on startup
        appScope.launch {
            val defaultSip = container.sipAccountRepository.getDefaultActiveAccount()
            if (defaultSip != null) {
                container.sipClientEngine.startAccountRegistration(defaultSip)
                if (defaultSip.balancePreset != SipBalancePreset.NONE && defaultSip.showBalanceInKeypad) {
                    container.sipBalanceFetcher.fetchBalance(defaultSip)
                }
            }
        }
    }
}
