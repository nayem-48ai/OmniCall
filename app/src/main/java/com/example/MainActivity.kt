package com.example

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.Alignment
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import com.example.ui.theme.OmniCallTheme
import com.example.ui.viewmodel.BlocklistViewModel
import com.example.ui.viewmodel.ContactsViewModel
import com.example.ui.viewmodel.DialerViewModel
import com.example.ui.viewmodel.OmniCallViewModelFactory
import com.example.ui.viewmodel.RecentsViewModel
import com.example.ui.viewmodel.RecordingsViewModel
import com.example.ui.viewmodel.SipSettingsViewModel
import kotlinx.serialization.Serializable

@Serializable object DialerRoute
@Serializable object RecentsRoute
@Serializable object ContactsRoute
@Serializable object SettingsRoute
@Serializable object RecordingsRoute
@Serializable object HiFiAudioSettingsRoute
@Serializable object BlocklistRoute

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

class MainActivity : ComponentActivity() {

    private val incomingDialNumber = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OmniCallApplication
        val factory = OmniCallViewModelFactory(app, app.container)
        incomingDialNumber.value = extractDialNumber(intent)

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
                    initialDialNumber = incomingDialNumber.value
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDialNumber(intent)?.let { number ->
            incomingDialNumber.value = number
        }
    }

    private fun extractDialNumber(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        return if (data.scheme == "tel") data.schemeSpecificPart else null
    }
}

@Composable
private fun OmniCallRoot(
    app: OmniCallApplication,
    factory: OmniCallViewModelFactory,
    initialDialNumber: String?
) {
    val dialerViewModel: DialerViewModel = viewModel(factory = factory)
    val recentsViewModel: RecentsViewModel = viewModel(factory = factory)
    val contactsViewModel: ContactsViewModel = viewModel(factory = factory)
    val recordingsViewModel: RecordingsViewModel = viewModel(factory = factory)
    val settingsViewModel: SipSettingsViewModel = viewModel(factory = factory)
    val blocklistViewModel: BlocklistViewModel = viewModel(factory = factory)

    val dialerState by dialerViewModel.uiState.collectAsStateWithLifecycle()
    val recentsState by recentsViewModel.uiState.collectAsStateWithLifecycle()
    val contactsState by contactsViewModel.uiState.collectAsStateWithLifecycle()
    val recordingsState by recordingsViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val blocklistState by blocklistViewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dialerViewModel.refreshHardwareEnvironment()
        settingsViewModel.refreshEnvironment()
        contactsViewModel.refreshContacts()
        recentsViewModel.refreshContactsIndex()
        blocklistViewModel.refreshContacts()
    }

    val activeCall by app.container.activeCallCoordinator.activeCall.collectAsStateWithLifecycle()
    val elapsedSeconds by app.container.activeCallCoordinator.elapsedSeconds.collectAsStateWithLifecycle()
    val isNearEar by app.container.activeCallCoordinator.isNearEar.collectAsStateWithLifecycle()
    val isCallUiMinimized by app.container.activeCallCoordinator.isCallUiMinimized.collectAsStateWithLifecycle()

    val defaultDialerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        dialerViewModel.refreshHardwareEnvironment()
        settingsViewModel.refreshEnvironment()
    }

    val requestDefaultDialer: () -> Unit = {
        app.container.telecomCallManager.createRequestDefaultDialerIntent()?.let {
            defaultDialerLauncher.launch(it)
        }
    }

    val promptedDefaultDialerOnce = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        dialerViewModel.refreshHardwareEnvironment()
        settingsViewModel.refreshEnvironment()
        contactsViewModel.refreshContacts()
        recentsViewModel.refreshContactsIndex()
        if (!promptedDefaultDialerOnce.value && !app.container.telecomCallManager.isDefaultDialer()) {
            promptedDefaultDialerOnce.value = true
            requestDefaultDialer()
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        contactsViewModel.refreshContacts()
        recentsViewModel.refreshContactsIndex()
        blocklistViewModel.refreshContacts()
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
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }

    LaunchedEffect(initialDialNumber) {
        if (!initialDialNumber.isNullOrBlank()) {
            dialerViewModel.setNumberInput(initialDialNumber)
        }
    }

    val hasLiveCall = activeCall.lifecycle != CallLifecycleState.IDLE &&
        activeCall.lifecycle != CallLifecycleState.ENDED
    val coordinator = app.container.activeCallCoordinator

    // Active / Incoming Call full-screen overlay (replaces Google Phone UI when OmniCall is Default Dialer)
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
            onMinimize = coordinator::minimizeCallUi
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isSettingsSubScreen = currentDestination?.hasRoute(RecordingsRoute::class) == true ||
        currentDestination?.hasRoute(HiFiAudioSettingsRoute::class) == true ||
        currentDestination?.hasRoute(BlocklistRoute::class) == true

    val navigateToTab: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
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
                                recentsViewModel.refreshContactsIndex()
                                blocklistViewModel.refreshContacts()
                            }
                        )
                    }
                    composable<SettingsRoute> {
                        SipSettingsScreen(
                            uiState = settingsState,
                            viewModel = settingsViewModel,
                            onRequestDefaultDialer = requestDefaultDialer,
                            onRequestPermissions = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.CALL_PHONE,
                                        Manifest.permission.RECORD_AUDIO,
                                        Manifest.permission.READ_CONTACTS
                                    )
                                )
                            },
                            onOpenRecordings = {
                                navController.navigate(RecordingsRoute)
                            },
                            onOpenHiFiAudioSettings = {
                                navController.navigate(HiFiAudioSettingsRoute)
                            },
                            onOpenBlocklist = {
                                navController.navigate(BlocklistRoute)
                            }
                        )
                    }
                    composable<RecordingsRoute> {
                        RecordingsScreen(
                            uiState = recordingsState,
                            viewModel = recordingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<HiFiAudioSettingsRoute> {
                        HiFiAudioSettingsScreen(
                            uiState = settingsState,
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<BlocklistRoute> {
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
                onEndCall = coordinator::endActiveCall
            )
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
