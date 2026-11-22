package com.vclient.android

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vclient.android.ui.screens.DEMO_NOTICE
import com.vclient.android.ui.screens.DemoScreen
import com.vclient.android.ui.screens.HomeScreen
import com.vclient.android.ui.theme.VClientTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DemoUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun waitForWorld() {
        // Generating the 32 x 32 chunk island happens in the background; wait until the welcome dialog shows.
        // The test clock is manual here, so step it ourselves while the background thread works.
        repeat(1_200) {
            if (compose.onAllNodesWithText("Start exploring").fetchSemanticsNodes().isNotEmpty()) return
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(50)
        }
        throw AssertionError("the Demo World never finished generating")
    }

    @Test
    fun homeOffersThePlayDemoWorldButtonWithTheRequiredMessage() {
        var opened = 0
        compose.setContent {
            VClientTheme { HomeScreen(MainViewModel(app), onOpenServers = {}, onPlayDemo = { opened++ }) }
        }
        assertEquals("This is a VYRN Demo World. Install Minecraft separately for the full game experience.", DEMO_NOTICE)
        compose.onNodeWithText(DEMO_NOTICE).performScrollTo().assertExists()
        compose.onNodeWithText("Play Demo World").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun demoWorldLoadsShowsTheNoticeStartsAndSavesWhenLeaving() {
        compose.mainClock.autoAdvance = false // the game loop asks for a new frame forever; step time by hand
        var exited = 0
        compose.setContent { VClientTheme { DemoScreen(onExit = { exited++ }) } }

        compose.onNodeWithText("Generating your world…").assertExists()
        waitForWorld()
        compose.onNodeWithText("VYRN Demo World").assertExists()
        compose.onAllNodesWithText(DEMO_NOTICE, substring = true).assertCountEquals(2) // banner + welcome dialog
        compose.onNodeWithText("Start exploring").performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Start exploring").assertDoesNotExist()
        compose.onAllNodesWithText(DEMO_NOTICE, substring = true).assertCountEquals(1)
        compose.onNodeWithText("Exit").assertExists()

        compose.onNodeWithText("Exit").performClick()
        assertEquals(1, exited)
        val saved = File(app.filesDir, "demo_world.vcw")
        assertTrue("world saved on the device: ${saved.length()} bytes", saved.length() > 50)
    }

    @Test
    fun bagShowsAnEmptyBackpackAndRecipesThatNeedIngredients() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VClientTheme { DemoScreen(onExit = {}) } }
        waitForWorld()
        compose.onNodeWithText("Start exploring").performClick()
        compose.mainClock.advanceTimeBy(100)

        compose.onNodeWithText("Bag & Crafting").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Recipes").assertExists()
        // Nothing is handed out: the first recipe is blocked until you have a log.
        compose.onAllNodesWithText("Need 1× Oak Log").onFirst().assertExists()
        compose.onNodeWithText("Planks ×4").assertExists()
        compose.onNodeWithText("Wooden Pickaxe").assertExists()
        compose.onAllNodesWithText("Stand near a Crafting Table").onFirst().assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Recipes").assertDoesNotExist()
    }
}
