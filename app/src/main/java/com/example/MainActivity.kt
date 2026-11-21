package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.preferences.AppSettingsState
import com.example.domain.model.CallLifecycleState
import com.example.ui.call.CallMiniWindowCard
import com.example.ui.call.InCallScreen
import com.example.ui.components.OutgoingRoutePickerSheet
import com.example.ui.contacts.ContactsScreen
import com.example.ui.dialer.DialerScreen
import com.example.ui.recents.RecentsScreen
import com.example.ui.recordings.RecordingsScreen
import com.example.ui.settings.BlocklistScreen
import com.example.ui.settings.HiFiAudioSettingsScreen
import com.example.ui.settings.SipSettingsScreen
import com.example.ui.settings.TelephonySettingsScreen
import com.example.ui.settings.ThemeSettingsScreen
import com.example.ui.theme.DoodleBackdrop
import com.example.ui.theme.OmniCallTheme
import com.example.ui.viewmodel.BlocklistViewModel
import com.example.ui.viewmodel.ContactsViewModel
import com.example.ui.viewmodel.DialerViewModel
import com.example.ui.viewmodel.OmniCallViewModelFactory
import com.example.ui.viewmodel.RecentsViewModel
import com.example.ui.viewmodel.RecordingsViewModel
import com.example.ui.viewmodel.SipSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable object DialerRoute
@Serializable object RecentsRoute
@Serializable object ContactsRoute
@Serializable object SettingsRoute
@Serializable object TelephonySettingsRoute
@Serializable object RecordingsRoute
@Serializable object HiFiAudioSettingsRoute
@Serializable object BlocklistRoute
@Serializable object ThemeSettingsRoute

private data class NavItem(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val NAV_ITEMS = listOf(
    NavItem(DialerRoute, "Keypad", Icons.Rounded.Dialpad, Icons.Outlined.Dialpad, "nav_keypad"),
    NavItem(RecentsRoute, "Recents", Icons.Rounded.History, Icons.Outlined.History, "nav_recents"),
    NavItem(ContactsRoute, "Contacts", Icons.Rounded.Contacts, Icons.Outlined.Contacts, "nav_contacts"),
    NavItem(SettingsRoute, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings, "nav_settings")
)

private data class CallbackLaunchRequest(
    val number: String,
    val preferSip: Boolean,
    val id: Long = System.nanoTime()
)

class MainActivity : ComponentActivity() {

    private val incomingDialNumber = mutableStateOf<String?>(null)
    private val openRecentsTrigger = mutableIntStateOf(0)
    private val callbackRequest = mutableStateOf<CallbackLaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OmniCallApplication
        val factory = OmniCallViewModelFactory(app, app.container)
        processIncomingIntent(app, intent)

        setContent {
            val settings by app.container.preferencesRepository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = AppSettingsState())

            OmniCallTheme(
                themePreference = settings.themePreference,
                colorPalette = settings.colorPalette,
                dynamicColor = settings.dynamicColorEnabled
            ) {
                OmniCallRoot(
                    app = app,
                    factory = factory,
                    activityStoreOwner = this@MainActivity,
                    doodleThemeEnabled = settings.doodleThemeEnabled,
                    initialDialNumber = incomingDialNumber.value,
                    openRecentsTrigger = openRecentsTrigger.intValue,
                    callbackRequest = callbackRequest.value,
                    onConsumeCallbackRequest = { callbackRequest.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val app = application as OmniCallApplication
        processIncomingIntent(app, intent)
    }

    private fun processIncomingIntent(app: OmniCallApplication, intent: Intent?) {
        if (intent == null) return
        extractDialNumber(intent)?.let { number ->
            incomingDialNumber.value = number
        }
        if (intent.getBooleanExtra(com.example.telecom.CallNotificationManager.EXTRA_EXPAND_ACTIVE_CALL, false)) {
            app.container.activeCallCoordinator.expandCallUi()
        }
        if (intent.getStringExtra(com.example.telecom.CallNotificationManager.EXTRA_OPEN_TAB) ==
            com.example.telecom.CallNotificationManager.TAB_RECENTS
        ) {
            app.container.callNotificationManager.cancelMissedCallNotifications()
            app.container.telecomCallManager.cancelMissedCallsNotification()
            openRecentsTrigger.intValue++
        }
        val callbackNum = intent.getStringExtra(com.example.telecom.CallNotificationManager.EXTRA_CALLBACK_NUMBER)
        if (!callbackNum.isNullOrBlank()) {
            app.container.callNotificationManager.cancelMissedCallNotifications()
            app.container.telecomCallManager.cancelMissedCallsNotification()
            val isSip = intent.getBooleanExtra(
                com.example.telecom.CallNotificationManager.EXTRA_CALLBACK_IS_SIP,
                false
            )
            callbackRequest.value = CallbackLaunchRequest(number = callbackNum, preferSip = isSip)
        }
    }

    private fun extractDialNumber(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        return when (data.scheme?.lowercase()) {
            "tel" -> data.schemeSpecificPart
            "sip" -> data.schemeSpecificPart.substringBefore(';')
            else -> null
        }
    }
}

@Composable
private fun OmniCallRoot(
    app: OmniCallApplication,
    factory: OmniCallViewModelFactory,
    activityStoreOwner: ViewModelStoreOwner,
    doodleThemeEnabled: Boolean,
    initialDialNumber: String?,
    openRecentsTrigger: Int,
    callbackRequest: CallbackLaunchRequest?,
    onConsumeCallbackRequest: () -> Unit
) {
    // Only DialerViewModel is needed on frame-1 launch; secondary tab ViewModels initialize lazily when opened.
    val dialerViewModel: DialerViewModel = viewModel(
        viewModelStoreOwner = activityStoreOwner,
        factory = factory
    )
    val dialerState by dialerViewModel.uiState.collectAsStateWithLifecycle()

    var refreshTick by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dialerViewModel.refreshHardwareEnvironment()
        refreshTick++
        app.container.sipClientEngine.ensureBackgroundServiceIfRegistered()
    }

    val activeCall by app.container.activeCallCoordinator.activeCall.collectAsStateWithLifecycle()
    val elapsedSeconds by app.container.activeCallCoordinator.elapsedSeconds.collectAsStateWithLifecycle()
    val isNearEar by app.container.activeCallCoordinator.isNearEar.collectAsStateWithLifecycle()
    val isCallUiMinimized by app.container.activeCallCoordinator.isCallUiMinimized.collectAsStateWithLifecycle()

    val defaultDialerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        dialerViewModel.refreshHardwareEnvironment()
        refreshTick++
    }

    val requestDefaultDialer: () -> Unit = {
        app.container.telecomCallManager.createRequestDefaultDialerIntent()?.let {
            defaultDialerLauncher.launch(it)
        }
    }

    val promptedDefaultDialerOnce = rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        dialerViewModel.refreshHardwareEnvironment()
        refreshTick++
        if (!promptedDefaultDialerOnce.value && !app.container.telecomCallManager.isDefaultDialer()) {
            promptedDefaultDialerOnce.value = true
            requestDefaultDialer()
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        dialerViewModel.refreshHardwareEnvironment(forceContactsRefresh = true)
        refreshTick++
    }

    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missingPerms = perms.filter { perm ->
            ContextCompat.checkSelfPermission(app, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            permissionLauncher.launch(missingPerms.toTypedArray())
        } else {
            val isDefault = withContext(Dispatchers.IO) {
                app.container.telecomCallManager.isDefaultDialer()
            }
            if (!promptedDefaultDialerOnce.value && !isDefault) {
                promptedDefaultDialerOnce.value = true
                requestDefaultDialer()
            }
        }
    }

    LaunchedEffect(initialDialNumber) {
        if (!initialDialNumber.isNullOrBlank()) {
            dialerViewModel.setNumberInput(initialDialNumber)
        }
    }

    val hasLiveCall = activeCall.lifecycle != CallLifecycleState.IDLE
    val coordinator = app.container.activeCallCoordinator

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isSettingsSubScreen = currentDestination?.hasRoute(RecordingsRoute::class) == true ||
        currentDestination?.hasRoute(HiFiAudioSettingsRoute::class) == true ||
        currentDestination?.hasRoute(BlocklistRoute::class) == true ||
        currentDestination?.hasRoute(TelephonySettingsRoute::class) == true ||
        currentDestination?.hasRoute(ThemeSettingsRoute::class) == true

    val navigateToTab: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(openRecentsTrigger) {
        if (openRecentsTrigger > 0) {
            navigateToTab(RecentsRoute)
        }
    }

    LaunchedEffect(callbackRequest) {
        val req = callbackRequest ?: return@LaunchedEffect
        onConsumeCallbackRequest()
        if (req.preferSip) {
            val sipRoute = dialerViewModel
                .buildAvailableRoutes(dialerState.environment)
                .filterIsInstance<com.example.domain.model.ResolvedOutgoingRoute.SipAccount>()
                .firstOrNull()
            if (sipRoute != null) {
                app.container.activeCallCoordinator.placeCall(req.number, sipRoute)
                return@LaunchedEffect
            }
        }
        dialerViewModel.initiateCall(req.number)
    }

    val requestStandardPermissions: () -> Unit = {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS
            )
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            DoodleBackdrop(enabled = doodleThemeEnabled)

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavRail = maxWidth >= 600.dp

                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavRail && !isSettingsSubScreen) {
                    NavigationBar {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail && !isSettingsSubScreen) {
                    NavigationRail {
                        NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = DialerRoute,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<DialerRoute> {
                        DialerScreen(
                            uiState = dialerState,
                            viewModel = dialerViewModel,
                            onOpenSipSettings = { navigateToTab(SettingsRoute) },
                            onRequestDefaultDialer = requestDefaultDialer
                        )
                    }
                    composable<RecentsRoute> {
                        val recentsViewModel: RecentsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val recentsState by recentsViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(refreshTick) {
                            recentsViewModel.refreshContactsIndex()
                            app.container.callNotificationManager.cancelMissedCallNotifications()
                            app.container.telecomCallManager.cancelMissedCallsNotification()
                        }
                        RecentsScreen(
                            uiState = recentsState,
                            onSearchChange = recentsViewModel::setSearchQuery,
                            onSelectFilter = recentsViewModel::setFilter,
                            onOpenCallHistory = recentsViewModel::openCallHistoryForNumber,
                            onCloseCallHistory = recentsViewModel::closeCallHistory,
                            onSelectContact = recentsViewModel::openContactDetail,
                            onCloseContactDetail = recentsViewModel::closeContactDetail,
                            onCallBack = { number ->
                                dialerViewModel.initiateCall(number)
                            },
                            onEditInKeypad = { number ->
                                dialerViewModel.setNumberInput(number)
                                navigateToTab(DialerRoute)
                            },
                            onDeleteItem = recentsViewModel::deleteEntry,
                            onDeleteSelected = recentsViewModel::deleteEntries,
                            onToggleBlockNumber = recentsViewModel::toggleBlockNumber
                        )
                    }
                    composable<ContactsRoute> {
                        val contactsViewModel: ContactsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val contactsState by contactsViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(refreshTick) {
                            contactsViewModel.refreshContacts()
                        }
                        ContactsScreen(
                            uiState = contactsState,
                            viewModel = contactsViewModel,
                            onRequestPermission = {
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            },
                            onCallNumber = { number ->
                                dialerViewModel.initiateCall(number)
                            },
                            onEditInKeypad = { number ->
                                dialerViewModel.setNumberInput(number)
                                navigateToTab(DialerRoute)
                            },
                            onContactsImported = {
                                dialerViewModel.refreshHardwareEnvironment(forceContactsRefresh = true)
                                refreshTick++
                            }
                        )
                    }
                    composable<SettingsRoute> {
                        val settingsViewModel: SipSettingsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(refreshTick) {
                            settingsViewModel.refreshEnvironment()
                        }
                        SipSettingsScreen(
                            uiState = settingsState,
                            viewModel = settingsViewModel,
                            onRequestDefaultDialer = requestDefaultDialer,
                            onRequestPermissions = requestStandardPermissions,
                            onOpenRecordings = {
                                navController.navigate(RecordingsRoute)
                            },
                            onOpenHiFiAudioSettings = {
                                navController.navigate(HiFiAudioSettingsRoute)
                            },
                            onOpenBlocklist = {
                                navController.navigate(BlocklistRoute)
                            },
                            onOpenTelephonySettings = {
                                navController.navigate(TelephonySettingsRoute)
                            },
                            onOpenThemeSettings = {
                                navController.navigate(ThemeSettingsRoute)
                            }
                        )
                    }
                    composable<ThemeSettingsRoute> {
                        val settingsViewModel: SipSettingsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        ThemeSettingsScreen(
                            uiState = settingsState,
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<TelephonySettingsRoute> {
                        val settingsViewModel: SipSettingsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(refreshTick) {
                            settingsViewModel.refreshEnvironment()
                        }
                        TelephonySettingsScreen(
                            uiState = settingsState,
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<RecordingsRoute> {
                        val recordingsViewModel: RecordingsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val recordingsState by recordingsViewModel.uiState.collectAsStateWithLifecycle()
                        RecordingsScreen(
                            uiState = recordingsState,
                            viewModel = recordingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<HiFiAudioSettingsRoute> {
                        val settingsViewModel: SipSettingsViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        HiFiAudioSettingsScreen(
                            uiState = settingsState,
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<BlocklistRoute> {
                        val blocklistViewModel: BlocklistViewModel = viewModel(
                            viewModelStoreOwner = activityStoreOwner,
                            factory = factory
                        )
                        val blocklistState by blocklistViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(refreshTick) {
                            blocklistViewModel.refreshContacts()
                        }
                        BlocklistScreen(
                            uiState = blocklistState,
                            viewModel = blocklistViewModel,
                            onRequestContactsPermission = {
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        // Phone-by-Google-style floating call mini window when call UI is minimized
        AnimatedVisibility(
            visible = hasLiveCall && isCallUiMinimized,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CallMiniWindowCard(
                callState = activeCall,
                elapsedSeconds = elapsedSeconds,
                onExpand = coordinator::expandCallUi,
                onAnswer = coordinator::answerIncomingCall,
                onDecline = coordinator::declineIncomingCall,
                onToggleMute = coordinator::toggleMute,
                onEndCall = coordinator::endActiveCall,
                onCancelAutoRecall = coordinator::cancelAutoRecall,
                onRecallNow = coordinator::triggerAutoRecallNow
            )
        }
            }

            // Active / Incoming Call full-screen overlay kept over NavHost so tab & scroll state survive calls
            if (hasLiveCall && !isCallUiMinimized) {
                BackHandler {
                    coordinator.minimizeCallUi()
                }
                InCallScreen(
                    callState = activeCall,
                    elapsedSeconds = elapsedSeconds,
                    isNearEar = isNearEar,
                    onToggleMute = coordinator::toggleMute,
                    onToggleHold = coordinator::toggleHold,
                    onSelectAudioRoute = coordinator::selectAudioRoute,
                    onToggleRecord = coordinator::toggleManualRecording,
                    onSendDtmf = coordinator::sendDtmf,
                    onAnswer = coordinator::answerIncomingCall,
                    onDecline = coordinator::declineIncomingCall,
                    onEndCall = coordinator::endActiveCall,
                    onCancelAutoRecall = coordinator::cancelAutoRecall,
                    onRecallNow = coordinator::triggerAutoRecallNow,
                    onMinimize = coordinator::minimizeCallUi
                )
            }
        }
    }

    // Global Outgoing Route Picker Sheet so dialing from Keypad, Recents, or Contacts
    // always opens the SIM 1 / SIM 2 / SIP picker when "Ask every time" is active.
    if (dialerState.showRoutePickerSheet) {
        OutgoingRoutePickerSheet(
            destination = dialerState.pendingCallDestination ?: dialerState.inputNumber,
            routes = dialerViewModel.buildAvailableRoutes(dialerState.environment),
            onSelectRoute = { dialerViewModel.confirmRouteSelection(it) },
            onDismiss = { dialerViewModel.dismissRoutePicker() }
        )
    }
}
