package com.dking.crocapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import com.dking.crocapp.croc.BinarySetupPhase
import com.dking.crocapp.croc.CrocBinaryManager
import com.dking.crocapp.ui.components.LiquidBackdrop
import com.dking.crocapp.ui.components.liquidGlass
import com.dking.crocapp.ui.guide.GuideScreen
import com.dking.crocapp.ui.history.HistoryScreen
import com.dking.crocapp.ui.navigation.CrocDestination
import com.dking.crocapp.ui.quick.QuickScreen
import com.dking.crocapp.ui.quick.QuickViewModel
import com.dking.crocapp.ui.receive.ReceiveScreen
import com.dking.crocapp.ui.receive.ReceiveViewModel
import com.dking.crocapp.ui.scanner.QrScannerScreen
import com.dking.crocapp.ui.send.SendScreen
import com.dking.crocapp.ui.send.SendViewModel
import com.dking.crocapp.ui.setup.CrocBinarySetupScreen
import com.dking.crocapp.ui.settings.SettingsScreen
import com.dking.crocapp.ui.settings.SettingsViewModel
import com.dking.crocapp.ui.theme.CrocTheme
import com.dking.crocapp.util.QrCodeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedContent = handleShareIntent(intent)
        val binaryManager = (application as CrocApp).binaryManager

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()

            val isDark = when (prefs.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            CrocTheme(
                darkTheme = isDark,
                amoledDark = prefs.amoledDark
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Soft colour field behind every tab — what the glass tints against.
                        LiquidBackdrop(modifier = Modifier.fillMaxSize())
                        CrocApp(
                            sharedContent = sharedContent,
                            settingsViewModel = settingsViewModel,
                            binaryManager = binaryManager
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?): SharedContent {
        if (intent == null) return SharedContent.None

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                // Check for file URI first
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    SharedContent.Files(listOf(uri))
                } else {
                    // Fallback to shared text (e.g. URLs, copied text from other apps)
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        SharedContent.Text(text)
                    } else {
                        SharedContent.None
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    SharedContent.Files(uris)
                } else {
                    SharedContent.None
                }
            }
            Intent.ACTION_VIEW -> {
                val rawData = intent.data?.toString().orEmpty()
                val code = QrCodeParser.parseCode(rawData)
                if (code.isNotEmpty()) SharedContent.ReceiveCode(code) else SharedContent.None
            }
            else -> SharedContent.None
        }
    }
}

sealed class SharedContent {
    data object None : SharedContent()
    data class Files(val uris: List<Uri>) : SharedContent()
    data class Text(val text: String) : SharedContent()
    data class ReceiveCode(val code: String) : SharedContent()
}

@Composable
fun CrocApp(
    sharedContent: SharedContent = SharedContent.None,
    settingsViewModel: SettingsViewModel,
    binaryManager: CrocBinaryManager
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val setupState by binaryManager.setupState.collectAsStateWithLifecycle()
    val retryScope = rememberCoroutineScope()

    val showBottomBar = currentRoute in CrocDestination.bottomNavItems.map { it.route }

    val sendViewModel: SendViewModel = viewModel()
    val receiveViewModel: ReceiveViewModel = viewModel()
    val quickViewModel: QuickViewModel = viewModel()

    var handledSharedContent by rememberSaveable { mutableStateOf(false) }
    if (sharedContent !is SharedContent.None && !handledSharedContent) {
        handledSharedContent = true
        when (sharedContent) {
            is SharedContent.Files -> {
                sendViewModel.addFiles(sharedContent.uris)
                // Navigate to Send tab so user sees shared files
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.navigate(CrocDestination.Send.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            is SharedContent.Text -> {
                sendViewModel.setSharedText(sharedContent.text)
                // Navigate to Send tab in text mode
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.navigate(CrocDestination.Send.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            is SharedContent.ReceiveCode -> {
                // croc:// deep link: fill the code and (if the croc binary is ready)
                // start receiving, mirroring the QR-scan flow.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    receiveViewModel.setCodeFromQr(sharedContent.code)
                    navController.navigate(CrocDestination.Receive.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                    if (binaryManager.isBinaryReady()) receiveViewModel.startReceive()
                }
            }
            else -> {}
        }
    }

    // Determine tab indices for directional animations
    val tabRoutes = CrocDestination.bottomNavItems.map { it.route }

    val shouldShowSetup = setupState.phase == BinarySetupPhase.Error ||
            (!binaryManager.isBinaryReady() && setupState.phase != BinarySetupPhase.Ready)

    if (shouldShowSetup) {
        CrocBinarySetupScreen(
            state = setupState,
            onRetry = {
                retryScope.launch(Dispatchers.IO) {
                    binaryManager.initialize()
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(PAGE_MS, easing = PageEasing)) { it } +
                        fadeIn(tween(PAGE_MS, easing = PageEasing)),
                exit = slideOutVertically(tween(PAGE_MS, easing = PageEasing)) { it } +
                        fadeOut(tween(PAGE_MS / 2))
            ) {
                // Floating liquid-glass pill instead of a flat full-width bar.
                NavigationBar(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .liquidGlass(
                            shape = NavPillShape,
                            tint = MaterialTheme.colorScheme.surfaceContainer,
                            fillAlpha = 0.6f,
                            shadowElevation = 8.dp
                        )
                        .clip(NavPillShape),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    CrocDestination.bottomNavItems.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = stringResource(destination.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(destination.labelRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = CrocDestination.Quick.route,
            modifier = Modifier.padding(paddingValues),
            // Page-style motion, like swiping between pages of a book:
            //  • tab ↔ tab: both pages slide a full screen width together over
            //    the fixed glass backdrop (no half-slide + crossfade mush).
            //  • opening Settings / History / Guide / Scanner: the new page slides
            //    in from the right, the old one drifts a quarter-width left and
            //    dims; back reverses it exactly.
            // Every part uses the same duration and easing, so nothing is still
            // moving after something else has stopped — that mismatch is what
            // reads as stutter.
            enterTransition = {
                val fromIndex = tabRoutes.indexOf(initialState.destination.route)
                val toIndex = tabRoutes.indexOf(targetState.destination.route)
                if (fromIndex >= 0 && toIndex >= 0) {
                    val direction = if (toIndex > fromIndex) 1 else -1
                    slideInHorizontally(pageSpec()) { direction * it } +
                            fadeIn(pageFloatSpec(), initialAlpha = 0.6f)
                } else {
                    slideInHorizontally(pageSpec()) { it }
                }
            },
            exitTransition = {
                val fromIndex = tabRoutes.indexOf(initialState.destination.route)
                val toIndex = tabRoutes.indexOf(targetState.destination.route)
                if (fromIndex >= 0 && toIndex >= 0) {
                    val direction = if (toIndex > fromIndex) -1 else 1
                    slideOutHorizontally(pageSpec()) { direction * it } +
                            fadeOut(pageFloatSpec(), targetAlpha = 0.6f)
                } else {
                    slideOutHorizontally(pageSpec()) { -it / 4 } +
                            fadeOut(pageFloatSpec(), targetAlpha = 0.4f)
                }
            },
            popEnterTransition = {
                slideInHorizontally(pageSpec()) { -it / 4 } +
                        fadeIn(pageFloatSpec(), initialAlpha = 0.4f)
            },
            popExitTransition = {
                slideOutHorizontally(pageSpec()) { it }
            }
        ) {
            composable(CrocDestination.Quick.route) {
                QuickScreen(
                    viewModel = quickViewModel,
                    onOpenScanner = { onCodeScanned ->
                        // Store callback and navigate to scanner
                        navController.navigate("scanner_quick")
                    },
                    onNavigateToSettings = {
                        navController.navigate(CrocDestination.Settings.route)
                    },
                    onNavigateToGuide = {
                        navController.navigate(CrocDestination.Guide.route)
                    }
                )
            }
            composable(CrocDestination.Send.route) {
                SendScreen(
                    viewModel = sendViewModel,
                    onNavigateToHistory = {
                        navController.navigate(CrocDestination.History.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(CrocDestination.Settings.route)
                    }
                )
            }
            composable(CrocDestination.Receive.route) {
                ReceiveScreen(
                    viewModel = receiveViewModel,
                    onOpenScanner = {
                        navController.navigate("scanner")
                    },
                    onNavigateToHistory = {
                        navController.navigate(CrocDestination.History.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(CrocDestination.Settings.route)
                    }
                )
            }
            composable(CrocDestination.History.route) {
                HistoryScreen(
                    onCodeSelected = { code ->
                        receiveViewModel.setCodeFromQr(code)
                        navController.navigate(CrocDestination.Receive.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(CrocDestination.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(CrocDestination.Guide.route) {
                GuideScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable("scanner") {
                QrScannerScreen(
                    onCodeScanned = { code ->
                        receiveViewModel.setCodeFromQr(code)
                        receiveViewModel.startReceive()
                        navController.navigate(CrocDestination.Receive.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable("scanner_quick") {
                QrScannerScreen(
                    onCodeScanned = { code ->
                        quickViewModel.startReceiveFromQr(code)
                        navController.navigate(CrocDestination.Quick.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

// ── Page motion ─────────────────────────────────────────────────
// Material 3 "emphasized" curve: quick start, long gentle landing.
private val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val PAGE_MS = 380

private fun pageSpec() = tween<IntOffset>(PAGE_MS, easing = PageEasing)
private fun pageFloatSpec() = tween<Float>(PAGE_MS, easing = PageEasing)

private val NavPillShape = RoundedCornerShape(32.dp)
