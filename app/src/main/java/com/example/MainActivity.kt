package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ads.ConsentManager
import com.example.ads.InterstitialAdManager
import com.example.data.QuranData
import com.example.model.RepeatMode
import com.example.model.SleepTimerOption
import com.example.model.Surah
import com.example.ui.components.AdBannerView
import com.example.ui.components.AddBookmarkDialog
import com.example.ui.components.FullPlayerBottomSheet
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.SleepTimerDialog
import com.example.ui.screens.BookmarksScreen
import com.example.ui.screens.DeveloperScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.RecitersScreen
import com.example.ui.screens.SurahsScreen
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.QuranAppTheme
import com.example.ui.viewmodel.QuranViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: QuranViewModel by viewModels()
    private val navigateToSurahEvent = MutableStateFlow<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ConsentManager.gatherConsent(this) {
            InterstitialAdManager.loadAd(this)
        }

        handleNavigationIntent(intent)

        setContent {
            QuranAppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val pendingSurahNavigation by navigateToSurahEvent.collectAsState()
                    QuranMainScreen(
                        viewModel = viewModel,
                        pendingSurahNavigation = pendingSurahNavigation,
                        onConsumedSurahNavigation = { navigateToSurahEvent.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        val targetSurahId = intent?.getIntExtra("NAVIGATE_TO_SURAH", -1) ?: -1
        if (targetSurahId > 0) {
            intent?.removeExtra("NAVIGATE_TO_SURAH")
            val surah = QuranData.getSurahById(targetSurahId)
            if (surah != null) {
                viewModel.playSurah(surah, autoPlay = false)
                navigateToSurahEvent.value = targetSurahId
            }
        }
    }
}

enum class QuranNavTab(val title: String, val icon: ImageVector, val tag: String) {
    SURAHS("السور", Icons.AutoMirrored.Filled.MenuBook, "nav_surahs"),
    RECITERS("القراء", Icons.Default.RecordVoiceOver, "nav_reciters"),
    DOWNLOADS("التنزيلات", Icons.Default.DownloadDone, "nav_downloads"),
    BOOKMARKS("الإشارات", Icons.Default.Bookmark, "nav_bookmarks")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranMainScreen(
    viewModel: QuranViewModel,
    pendingSurahNavigation: Int? = null,
    onConsumedSurahNavigation: () -> Unit = {},
    isDeveloperPanelEnabled: Boolean = viewModel.isDeveloperToolsEnabled()
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDeveloperScreen by remember { mutableStateOf(false) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var bookmarkSurahTarget by remember { mutableStateOf<Surah?>(null) }

    val playerState by viewModel.playerState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("MainActivity", "POST_NOTIFICATIONS granted.")
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS denied. Audio playback continues without notification controls.")
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(pendingSurahNavigation) {
        if (pendingSurahNavigation != null && pendingSurahNavigation > 0) {
            showDeveloperScreen = false
            selectedTab = 0
            showFullPlayer = false
            onConsumedSurahNavigation()
        }
    }

    LaunchedEffect(isDeveloperPanelEnabled) {
        if (!isDeveloperPanelEnabled) {
            showDeveloperScreen = false
        }
    }

    LaunchedEffect(playerState.errorMessage) {
        playerState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
        }
    }

    val isDeveloperViewActive = isDeveloperPanelEnabled && showDeveloperScreen

    BackHandler(enabled = showFullPlayer || isDeveloperViewActive || selectedTab != 0) {
        when {
            showFullPlayer -> showFullPlayer = false
            isDeveloperViewActive -> showDeveloperScreen = false
            selectedTab != 0 -> selectedTab = 0
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = if (isDeveloperViewActive) "أدوات المطوّر (نسخة التطوير)" else "القرآن الكريم",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = "القارئ: ${playerState.currentReciter?.name ?: "مشاري العفاسي"}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (isDeveloperViewActive) {
                        IconButton(
                            onClick = { showDeveloperScreen = false },
                            modifier = Modifier.testTag("developer_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "العودة",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showSleepTimerDialog = true },
                            modifier = Modifier.testTag("topbar_sleep_timer")
                        ) {
                            Icon(
                                imageVector = Icons.Default.NightsStay,
                                contentDescription = "مؤقت النوم",
                                tint = if (playerState.sleepTimerOption != SleepTimerOption.OFF) GoldAccent else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (playerState.sleepTimerSecondsRemaining > 0) {
                            val mins = playerState.sleepTimerSecondsRemaining / 60
                            val secs = playerState.sleepTimerSecondsRemaining % 60
                            Surface(
                                shape = CircleShape,
                                color = GoldAccent,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%02d:%02d", mins, secs),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    ),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (isDeveloperPanelEnabled) {
                        IconButton(
                            onClick = { showDeveloperScreen = !showDeveloperScreen },
                            modifier = Modifier.testTag("topbar_developer_settings")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "لوحة المطور (للتطوير فقط)",
                                tint = if (isDeveloperViewActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                MiniPlayerBar(
                    playerState = playerState,
                    onBarClick = { showFullPlayer = true },
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = { viewModel.playNext() },
                    onPrevClick = { viewModel.playPrevious() }
                )

                AdBannerView(
                    modifier = Modifier.fillMaxWidth()
                )

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    QuranNavTab.values().forEachIndexed { index, tab ->
                        val isSelected = !isDeveloperViewActive && selectedTab == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                showDeveloperScreen = false
                                selectedTab = index
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag(tab.tag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isDeveloperViewActive) {
                DeveloperScreen(
                    viewModel = viewModel,
                    isAccessAllowed = isDeveloperPanelEnabled
                )
            } else {
                when (selectedTab) {
                    0 -> SurahsScreen(
                        viewModel = viewModel,
                        onSurahSelected = { surah ->
                            val isNewSurah = playerState.currentSurah?.id != surah.id
                            val activity = context as? Activity
                            if (activity != null && isNewSurah) {
                                InterstitialAdManager.showAdBeforeSurah(
                                    activity = activity,
                                    isNewSurah = true
                                ) {
                                    viewModel.playSurah(surah)
                                    showFullPlayer = true
                                }
                            } else {
                                if (isNewSurah) {
                                    viewModel.playSurah(surah)
                                } else if (!playerState.isPlaying) {
                                    viewModel.resume()
                                }
                                showFullPlayer = true
                            }
                        },
                        onOpenAddBookmark = { surah ->
                            bookmarkSurahTarget = surah
                        }
                    )
                    1 -> RecitersScreen(viewModel = viewModel)
                    2 -> DownloadsScreen(viewModel = viewModel)
                    3 -> BookmarksScreen(viewModel = viewModel)
                }
            }
        }
    }

    if (showFullPlayer && playerState.currentSurah != null) {
        val currentSurah = playerState.currentSurah!!
        val isDownloaded = viewModel.isSurahDownloaded(currentSurah.id)

        FullPlayerBottomSheet(
            playerState = playerState,
            isDownloaded = isDownloaded,
            onDismiss = { showFullPlayer = false },
            onPlayPauseClick = { viewModel.togglePlayPause() },
            onNextClick = { viewModel.playNext() },
            onPrevClick = { viewModel.playPrevious() },
            onSeek = { viewModel.seekTo(it) },
            onToggleRepeat = {
                val nextMode = when (playerState.repeatMode) {
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.NONE
                    RepeatMode.NONE -> RepeatMode.ALL
                }
                viewModel.setRepeatMode(nextMode)
                val msg = when (nextMode) {
                    RepeatMode.ALL -> "تشغيل تلقائي متتالي للسور"
                    RepeatMode.ONE -> "تكرار السورة الحالية"
                    RepeatMode.NONE -> "إيقاف التكرار عند نهاية السورة"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onCycleSpeed = {
                val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                val currentIdx = speeds.indexOfFirst { kotlin.math.abs(it - playerState.playbackSpeed) < 0.05f }
                val nextSpeed = speeds[(currentIdx + 1) % speeds.size]
                viewModel.setPlaybackSpeed(nextSpeed)
                Toast.makeText(context, "سرعة القراءة: ${nextSpeed}x", Toast.LENGTH_SHORT).show()
            },
            onOpenSleepTimer = { showSleepTimerDialog = true },
            onOpenAddBookmark = { bookmarkSurahTarget = currentSurah },
            onDownloadClick = { viewModel.startDownload(currentSurah) }
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentOption = playerState.sleepTimerOption,
            onOptionSelected = { option ->
                viewModel.setSleepTimer(option)
                if (option.totalSeconds > 0) {
                    Toast.makeText(context, "تم ضبط مؤقت النوم: ${option.label}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "تم إلغاء مؤقت النوم", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showSleepTimerDialog = false }
        )
    }

    bookmarkSurahTarget?.let { surah ->
        val pos = if (playerState.currentSurah?.id == surah.id) playerState.currentPositionMs else 0L
        AddBookmarkDialog(
            surah = surah,
            positionMs = pos,
            onSave = { note ->
                viewModel.addBookmark(surah, note)
                Toast.makeText(context, "تم حفظ الإشارة المرجعية لسورة ${surah.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { bookmarkSurahTarget = null }
        )
    }
}
