package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.example.data.DefaultReciters
import com.example.data.QuranData
import com.example.model.PlayerState
import com.example.security.DeveloperAccessPolicy
import com.example.ui.components.AdBannerView
import com.example.ui.components.BannerAdState
import com.example.ui.components.BannerStateSemanticsKey
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.SurahItemCard
import com.example.ui.components.resolveBannerHeightDp
import com.example.ui.components.shouldAttachAdBannerView
import com.example.ui.components.shouldRequestBannerAd
import com.example.ui.screens.DeveloperScreen
import com.example.ui.theme.QuranAppTheme
import com.example.ui.viewmodel.QuranViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppFixesRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `developer access policy disables developer tools in user release builds`() {
        assertFalse(DeveloperAccessPolicy.isDeveloperToolsEnabled(isDebugBuild = false))
        assertTrue(DeveloperAccessPolicy.isDeveloperToolsEnabled(isDebugBuild = true))
    }

    @Test
    fun `developer-only ViewModel mutations are blocked when developer tools are disabled while backup remains intact`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = QuranViewModel(app)
        val db = QuranApp.database

        viewModel.developerToolsEnabledForTest = false
        try {
            // 1. Adding custom reciter must fail and not persist in Room
            val added = viewModel.addCustomReciter(
                name = "قارئ غير مصرح",
                serverUrl = "https://example.com/audio/",
                availableCount = 114
            )
            assertFalse(added)
            assertFalse(db.reciterDao().getAllRecitersOnce().any { it.name == "قارئ غير مصرح" })

            // 2. Deleting an existing custom reciter must be blocked when disabled
            val seeded = com.example.data.local.ReciterEntity(
                id = "custom_guard_test",
                name = "قارئ محلي محفوظ",
                serverUrl = "https://example.com/saved/",
                availableSurahsCount = 114,
                availableSurahsCsv = "",
                isCustom = true,
                subInfo = "قارئ مخصص محلي"
            )
            db.reciterDao().insertReciter(seeded)
            viewModel.deleteCustomReciter("custom_guard_test")
            kotlinx.coroutines.delay(50)
            assertEquals("custom_guard_test", db.reciterDao().getReciterById("custom_guard_test")?.id)

            // 3. Developer test notification trigger must be blocked
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val beforeCount = Shadows.shadowOf(nm).allNotifications.size
            viewModel.triggerFridayReminderTest()
            assertEquals(beforeCount, Shadows.shadowOf(nm).allNotifications.size)

            // 4. Backup export/import must remain functional
            val json = viewModel.exportBackupJson()
            assertTrue(json.contains("\"version\""))
            val importResult = viewModel.importBackupJsonWithResult(json)
            assertTrue(importResult.success)
        } finally {
            viewModel.developerToolsEnabledForTest = null
        }
    }

    @Test
    fun `DeveloperScreen direct invocation renders nothing when access is disallowed`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = QuranViewModel(app)

        composeRule.setContent {
            QuranAppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DeveloperScreen(
                        viewModel = viewModel,
                        isAccessAllowed = false
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("developer_screen").assertDoesNotExist()
    }

    @Test
    fun `MainActivity developer entry point is completely unavailable when access is disallowed`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = QuranViewModel(app)
        var devEnabled by mutableStateOf(false)

        composeRule.setContent {
            QuranAppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    QuranMainScreen(
                        viewModel = viewModel,
                        isDeveloperPanelEnabled = devEnabled
                    )
                }
            }
        }

        // In user/release mode, neither the settings button nor the developer screen may exist
        composeRule.onNodeWithTag("topbar_developer_settings").assertDoesNotExist()
        composeRule.onNodeWithTag("developer_screen").assertDoesNotExist()

        // Even if previously opened in debug and then disabled, access must immediately close
        devEnabled = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("topbar_developer_settings").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("developer_screen").assertIsDisplayed()

        devEnabled = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("topbar_developer_settings").assertDoesNotExist()
        composeRule.onNodeWithTag("developer_screen").assertDoesNotExist()
    }

    @Test
    fun `banner helper functions enforce non-zero height and strict consent-network gating`() {
        assertEquals(56.dp, resolveBannerHeightDp(0))
        assertEquals(50.dp, resolveBannerHeightDp(32))
        assertEquals(60.dp, resolveBannerHeightDp(60))
        assertEquals(80.dp, resolveBannerHeightDp(120))

        assertTrue(shouldRequestBannerAd(isAdsReady = true, canRequestAds = true, isNetworkAvailable = true))
        assertFalse(shouldRequestBannerAd(isAdsReady = false, canRequestAds = true, isNetworkAvailable = true))
        assertFalse(shouldRequestBannerAd(isAdsReady = true, canRequestAds = false, isNetworkAvailable = true))
        assertFalse(shouldRequestBannerAd(isAdsReady = true, canRequestAds = true, isNetworkAvailable = false))

        assertFalse(shouldAttachAdBannerView(isAdLoaded = false, isAdsReady = true, canRequestAds = false))
        assertTrue(shouldAttachAdBannerView(isAdLoaded = true, isAdsReady = false, canRequestAds = true))
    }

    @Test
    fun `MiniPlayerBar does not shift vertically across banner states or screen width orientation changes`() {
        val sampleSurah = QuranData.getSurahById(18)!!
        val playerState = PlayerState(
            currentSurah = sampleSurah,
            currentReciter = DefaultReciters.defaultReciter,
            isPlaying = true
        )

        var bannerState by mutableStateOf(BannerAdState.LOADED)
        var screenWidthDp by mutableStateOf(360)
        var orientation by mutableStateOf(Configuration.ORIENTATION_PORTRAIT)

        composeRule.setContent {
            val baseConfig = LocalConfiguration.current
            val customConfig = Configuration(baseConfig).apply {
                this.screenWidthDp = screenWidthDp
                this.orientation = orientation
            }
            QuranAppTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalConfiguration provides customConfig
                ) {
                    Column(modifier = Modifier.width(screenWidthDp.dp)) {
                        MiniPlayerBar(
                            playerState = playerState,
                            onBarClick = {},
                            onPlayPauseClick = {},
                            onNextClick = {},
                            onPrevClick = {}
                        )
                        AdBannerView(
                            modifier = Modifier.fillMaxWidth(),
                            canRequestAdsProvider = { bannerState != BannerAdState.CONSENT_DENIED },
                            networkAvailableProvider = { bannerState != BannerAdState.OFFLINE },
                            adsReadyOverride = bannerState == BannerAdState.LOADED || bannerState == BannerAdState.LOADING,
                            bannerAdStateOverride = bannerState,
                            attachPlatformAdView = false
                        )
                        NavigationBar {
                            NavigationBarItem(
                                selected = true,
                                onClick = {},
                                icon = { Text("S") },
                                label = { Text("السور") }
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val baselineMiniBounds = composeRule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot()
        val baselineBannerBounds = composeRule.onNodeWithTag("admob_banner_container").getUnclippedBoundsInRoot()
        assertTrue(baselineBannerBounds.height >= 50.dp)
        assertTrue(baselineMiniBounds.bottom <= baselineBannerBounds.top + 0.5.dp)

        // Verify MiniPlayerBar stays anchored in exact same position across ALL failure/offline/consent states
        val statesToTest = listOf(
            BannerAdState.LOADING,
            BannerAdState.FAILED,
            BannerAdState.OFFLINE,
            BannerAdState.CONSENT_DENIED,
            BannerAdState.LOADED
        )
        for (state in statesToTest) {
            bannerState = state
            composeRule.waitForIdle()

            composeRule.onNode(SemanticsMatcher.expectValue(BannerStateSemanticsKey, state)).assertIsDisplayed()
            val miniBounds = composeRule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot()
            val bannerBounds = composeRule.onNodeWithTag("admob_banner_container").getUnclippedBoundsInRoot()

            assertTrue("Banner collapsed in state $state", bannerBounds.height >= 50.dp)
            assertEquals("Banner height changed in state $state", baselineBannerBounds.height.value, bannerBounds.height.value, 0.5f)
            assertEquals("MiniPlayerBar top shifted in state $state", baselineMiniBounds.top.value, miniBounds.top.value, 0.5f)
            assertEquals("MiniPlayerBar bottom shifted in state $state", baselineMiniBounds.bottom.value, miniBounds.bottom.value, 0.5f)
            assertTrue("Banner overlapped MiniPlayerBar in state $state", miniBounds.bottom <= bannerBounds.top + 0.5.dp)
        }

        // Simulate screen rotation to landscape (640dp wide) and back to portrait (360dp wide)
        screenWidthDp = 640
        orientation = Configuration.ORIENTATION_LANDSCAPE
        bannerState = BannerAdState.FAILED
        composeRule.waitForIdle()

        val landscapeBannerBounds = composeRule.onNodeWithTag("admob_banner_container").getUnclippedBoundsInRoot()
        val landscapeMiniBounds = composeRule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot()
        assertTrue(landscapeBannerBounds.height >= 50.dp)
        assertTrue(landscapeMiniBounds.bottom <= landscapeBannerBounds.top + 0.5.dp)

        screenWidthDp = 360
        orientation = Configuration.ORIENTATION_PORTRAIT
        composeRule.waitForIdle()
        val restoredMiniBounds = composeRule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot()
        assertEquals(baselineMiniBounds.top.value, restoredMiniBounds.top.value, 0.5f)
    }

    @Test
    fun `MiniPlayerBar buttons have consistent sizes, alignment, actions, and no overlap on narrow 320dp screen`() {
        val sampleSurah = QuranData.getSurahById(2)!! // البقرة
        val playerState = PlayerState(
            currentSurah = sampleSurah,
            currentReciter = DefaultReciters.defaultReciter,
            isPlaying = true,
            sleepTimerOption = com.example.model.SleepTimerOption.MINUTES_15,
            sleepTimerSecondsRemaining = 600L
        )

        var prevClicks = 0
        var toggleClicks = 0
        var nextClicks = 0

        composeRule.setContent {
            QuranAppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(modifier = Modifier.width(320.dp)) {
                        MiniPlayerBar(
                            playerState = playerState,
                            onBarClick = {},
                            onPlayPauseClick = { toggleClicks++ },
                            onNextClick = { nextClicks++ },
                            onPrevClick = { prevClicks++ }
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val barBounds = composeRule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot()
        val prevBounds = composeRule.onNodeWithTag("mini_player_prev").getUnclippedBoundsInRoot()
        val toggleBounds = composeRule.onNodeWithTag("mini_player_toggle").getUnclippedBoundsInRoot()
        val nextBounds = composeRule.onNodeWithTag("mini_player_next").getUnclippedBoundsInRoot()

        // All 3 buttons must have consistent 40dp x 40dp dimensions
        assertEquals(40f, prevBounds.width.value, 0.5f)
        assertEquals(40f, prevBounds.height.value, 0.5f)
        assertEquals(40f, toggleBounds.width.value, 0.5f)
        assertEquals(40f, toggleBounds.height.value, 0.5f)
        assertEquals(40f, nextBounds.width.value, 0.5f)
        assertEquals(40f, nextBounds.height.value, 0.5f)

        // Vertically aligned
        assertTrue(abs(prevBounds.top.value - toggleBounds.top.value) < 0.5f)
        assertTrue(abs(toggleBounds.top.value - nextBounds.top.value) < 0.5f)

        // Strictly non-overlapping in fixed LTR order: prev | toggle | next, and inside bar bounds
        assertTrue(prevBounds.left >= barBounds.left)
        assertTrue(prevBounds.right <= toggleBounds.left + 0.5.dp)
        assertTrue(toggleBounds.right <= nextBounds.left + 0.5.dp)
        assertTrue(nextBounds.right <= barBounds.right + 0.5.dp)

        // Functions work cleanly
        composeRule.onNodeWithTag("mini_player_prev").performClick()
        composeRule.onNodeWithTag("mini_player_toggle").performClick()
        composeRule.onNodeWithTag("mini_player_next").performClick()
        assertEquals(1, prevClicks)
        assertEquals(1, toggleClicks)
        assertEquals(1, nextClicks)
    }

    @Test
    fun `SurahItemCard keeps minimal layout with 16sp Arabic name, 24dp badge, uniform 34dp play button, and no extra buttons`() {
        val surah = QuranData.getSurahById(3)!! // آل عمران

        composeRule.setContent {
            QuranAppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(modifier = Modifier.width(412.dp)) {
                        SurahItemCard(
                            surah = surah,
                            isPlaying = false,
                            isCurrent = false,
                            isAvailableForReciter = true,
                            onCardClick = {},
                            onPlayClick = {}
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val node = composeRule.onNodeWithTag("surah_name_3", useUnmergedTree = true)
        node.assertIsDisplayed()

        val textLayoutResults = mutableListOf<TextLayoutResult>()
        val semanticsNode = node.fetchSemanticsNode()
        semanticsNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(textLayoutResults)
        assertTrue(textLayoutResults.isNotEmpty())
        val layout = textLayoutResults.first()
        assertEquals(16.sp, layout.layoutInput.style.fontSize)
        assertFalse("Arabic surah name overflowed height", layout.didOverflowHeight)

        val nameBounds = node.getUnclippedBoundsInRoot()
        val badgeNode = composeRule.onNodeWithTag("surah_badge_3", useUnmergedTree = true)
        badgeNode.assertIsDisplayed()
        val badgeBounds = badgeNode.getUnclippedBoundsInRoot()
        assertEquals(24f, badgeBounds.width.value, 0.5f)
        assertEquals(24f, badgeBounds.height.value, 0.5f)

        val playBounds = composeRule.onNodeWithTag("play_button_3", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertEquals(34f, playBounds.width.value, 0.5f)
        assertEquals(34f, playBounds.height.value, 0.5f)
        assertTrue("Surah badge icon must be smaller than play button", badgeBounds.width < playBounds.width)

        assertTrue(nameBounds.width > 0.dp && nameBounds.height > 0.dp)
        // In RTL, name is to the right of the play button, and badge is to the right of the name
        assertTrue("Surah name overlapped play button", playBounds.right <= nameBounds.left + 0.5.dp)
        assertTrue("Surah badge overlapped surah name", nameBounds.right <= badgeBounds.left + 0.5.dp)

        // Extra clutter elements must NOT exist on the Surah card
        composeRule.onNodeWithTag("fav_button_3", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("bookmark_button_3", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("download_button_3", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("${surah.versesCount} آية", substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(surah.englishName, substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `All 114 Surah badges and play buttons have identical uniform dimensions under large fontScale and narrow screens`() {
        var currentSurahIndex by mutableStateOf(0)
        val allSurahs = QuranData.allSurahs
        assertEquals(114, allSurahs.size)

        composeRule.setContent {
            val currentDensity = LocalDensity.current
            val largeFontDensity = androidx.compose.ui.unit.Density(
                density = currentDensity.density,
                fontScale = 1.5f
            )
            QuranAppTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalDensity provides largeFontDensity
                ) {
                    val surah = allSurahs[currentSurahIndex]
                    Box(modifier = Modifier.width(320.dp)) {
                        SurahItemCard(
                            surah = surah,
                            isPlaying = surah.id % 2 == 0,
                            isCurrent = surah.id % 3 == 0,
                            isAvailableForReciter = surah.id != 50,
                            onCardClick = {},
                            onPlayClick = {}
                        )
                    }
                }
            }
        }

        var baselineTextFontSizePx = -1f
        for (idx in allSurahs.indices) {
            currentSurahIndex = idx
            composeRule.waitForIdle()
            val surah = allSurahs[idx]

            val badgeBounds = composeRule.onNodeWithTag("surah_badge_${surah.id}", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertEquals("Width mismatch for surah ${surah.id}", 24f, badgeBounds.width.value, 0.2f)
            assertEquals("Height mismatch for surah ${surah.id}", 24f, badgeBounds.height.value, 0.2f)

            val playBounds = composeRule.onNodeWithTag("play_button_${surah.id}", useUnmergedTree = true)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertEquals("Play button width mismatch for surah ${surah.id}", 34f, playBounds.width.value, 0.2f)
            assertEquals("Play button height mismatch for surah ${surah.id}", 34f, playBounds.height.value, 0.2f)

            val textNode = composeRule.onNodeWithTag("surah_badge_text_${surah.id}", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            textNode.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertTrue("Missing text layout for surah ${surah.id}", layouts.isNotEmpty())
            val layout = layouts.first()
            assertEquals("Badge text wrapped for surah ${surah.id}: ${layout.size}", 1, layout.lineCount)
            assertFalse(
                "Badge text overflowed width for surah ${surah.id}: size=${layout.size}, lineW=${layout.getLineRight(0) - layout.getLineLeft(0)}, maxW=${layout.layoutInput.constraints.maxWidth}",
                layout.didOverflowWidth
            )

            val renderedFontPx = layout.layoutInput.density.run { layout.layoutInput.style.fontSize.toPx() }
            val expectedFontPx = layout.layoutInput.density.run { 9.5.dp.toPx() }
            assertEquals("Badge font failed to lock to 9.5dp under fontScale=1.5 for surah ${surah.id}", expectedFontPx, renderedFontPx, 0.5f)
            if (baselineTextFontSizePx < 0f) {
                baselineTextFontSizePx = renderedFontPx
            } else {
                assertEquals("Visual glyph size differed for surah ${surah.id}", baselineTextFontSizePx, renderedFontPx, 0.5f)
            }
        }
    }

    @Test
    fun `Main screen matches reference visual structure with topbar search beside sleep timer, filter pills, and preserved banner space`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = QuranViewModel(app)

        composeRule.setContent {
            QuranAppTheme(darkTheme = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    QuranMainScreen(
                        viewModel = viewModel,
                        isDeveloperPanelEnabled = false
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // 1. Search icon next to night/sleep timer icon in TopAppBar (RTL: sleep timer at far left, search next to its right)
        val searchBounds = composeRule.onNodeWithTag("topbar_search_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        val sleepBounds = composeRule.onNodeWithTag("topbar_sleep_timer").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Search icon must sit immediately next to sleep timer in RTL", sleepBounds.right <= searchBounds.left + 0.5.dp)

        // 2. Search bar hidden initially, expands when TopAppBar search icon is clicked
        composeRule.onNodeWithTag("surah_search_input").assertDoesNotExist()
        composeRule.onNodeWithTag("topbar_search_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("surah_search_input").assertIsDisplayed()
        composeRule.onNodeWithTag("surah_search_input").performTextInput("الكهف")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("surah_card_18").assertIsDisplayed()
        composeRule.onNodeWithTag("topbar_search_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("surah_search_input").assertDoesNotExist()

        // 3. Filter pills "الجميع (114)" and "المفضلة (0)" at top of list
        composeRule.onNodeWithTag("filter_all_surahs").assertIsDisplayed()
        composeRule.onNodeWithText("الجميع (114)").assertIsDisplayed()
        composeRule.onNodeWithTag("filter_favorite_surahs").assertIsDisplayed()

        // 4. First Surah numbered 1 appears cleanly and banner space stays between MiniPlayerBar and NavigationBar
        composeRule.onNodeWithTag("surah_badge_1", useUnmergedTree = true).assertIsDisplayed()
        val miniBounds = composeRule.onNodeWithTag("mini_player_bar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val bannerBounds = composeRule.onNodeWithTag("admob_banner_container").assertIsDisplayed().getUnclippedBoundsInRoot()
        val navBounds = composeRule.onNodeWithTag("nav_surahs").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Banner space was shrunk or removed", bannerBounds.height >= 50.dp)
        assertTrue("Banner must sit below MiniPlayerBar", miniBounds.bottom <= bannerBounds.top + 0.5.dp)
        assertTrue("Banner must sit above NavigationBar", bannerBounds.bottom <= navBounds.top + 0.5.dp)
    }

    @Test
    fun `MiniPlayerBar surah badge matches unified 24dp size`() {
        val sampleSurah = QuranData.getSurahById(114)!!
        val playerState = PlayerState(
            currentSurah = sampleSurah,
            currentReciter = DefaultReciters.defaultReciter,
            isPlaying = false
        )

        composeRule.setContent {
            QuranAppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(modifier = Modifier.width(360.dp)) {
                        MiniPlayerBar(
                            playerState = playerState,
                            onBarClick = {},
                            onPlayPauseClick = {},
                            onNextClick = {},
                            onPrevClick = {}
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val miniBadgeBounds = composeRule.onNodeWithTag("mini_player_surah_badge", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertEquals(24f, miniBadgeBounds.width.value, 0.2f)
        assertEquals(24f, miniBadgeBounds.height.value, 0.2f)
    }

    @Test
    fun `backup XML rules explicitly restrict cloud backup and device transfer to non-personal app prefs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backupParser = context.resources.getXml(R.xml.backup_rules)
        val includedPaths = mutableListOf<String>()
        var event = backupParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && backupParser.name == "include") {
                val domain = backupParser.getAttributeValue(null, "domain")
                val path = backupParser.getAttributeValue(null, "path")
                if (domain == "sharedpref" && path != null) {
                    includedPaths.add(path)
                }
            }
            event = backupParser.next()
        }
        assertEquals(listOf("quran_prefs.xml"), includedPaths)

        val extractionParser = context.resources.getXml(R.xml.data_extraction_rules)
        val cloudPaths = mutableListOf<String>()
        var extEvent = extractionParser.eventType
        while (extEvent != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (extEvent == org.xmlpull.v1.XmlPullParser.START_TAG && extractionParser.name == "include") {
                val domain = extractionParser.getAttributeValue(null, "domain")
                val path = extractionParser.getAttributeValue(null, "path")
                if (domain == "sharedpref" && path != null) {
                    cloudPaths.add(path)
                }
            }
            extEvent = extractionParser.next()
        }
        assertEquals(listOf("quran_prefs.xml", "quran_prefs.xml"), cloudPaths)
    }
}
