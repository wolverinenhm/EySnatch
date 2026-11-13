package com.eysnatch

import android.app.Activity
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eysnatch.core.PhoneAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensAndActionsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // UiAutomation normally switches every other accessibility service off while it is connected.
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    private fun <T : Activity> opens(type: Class<T>) {
        ActivityScenario.launch(type).use { scenario ->
            scenario.onActivity { assertNotNull(it.window.decorView) }
        }
    }

    @Test
    fun everyScreenOpens() {
        opens(MainActivity::class.java)
        opens(LiveActivity::class.java)
        opens(CalibrateActivity::class.java)
        opens(SettingsActivity::class.java)
    }

    @Test
    fun accessibilityServiceCanPressHome() {
        val svc = switchOnAccessibilityService()
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            svc.perform(PhoneAction.HOME, "test")
            Thread.sleep(2500)
            val focus = focusedWindow()
            Log.i("EySnatchTest", "ACTIONS TEST RAN, focus=$focus")
            assertFalse("Home did not leave the app: $focus", focus.contains("com.eysnatch"))
        }
    }

    @Test
    fun accessibilityServiceAcceptsSwipesAndScrolls() {
        val svc = switchOnAccessibilityService()
        ActivityScenario.launch(SettingsActivity::class.java).use {
            Thread.sleep(1500)
            val actions = listOf(
                PhoneAction.SCROLL_DOWN, PhoneAction.SCROLL_UP, PhoneAction.SWIPE_LEFT,
                PhoneAction.SWIPE_RIGHT, PhoneAction.FORWARD, PhoneAction.BACKWARD,
            )
            for (a in actions) {
                svc.perform(a, "test")
                Thread.sleep(700)
            }
            Log.i("EySnatchTest", "SWIPES TEST RAN")
        }
    }

    /** Enables the service the way a user would (secure setting) and waits until Android connects it. */
    private fun switchOnAccessibilityService(): EySnatchAccessibilityService {
        EySnatchAccessibilityService.instance?.let { return it }
        val me = "${instrumentation.targetContext.packageName}/${EySnatchAccessibilityService::class.java.name}"
        shell("settings put secure enabled_accessibility_services $me")
        shell("settings put secure accessibility_enabled 1")
        repeat(160) {
            EySnatchAccessibilityService.instance?.let { return it }
            Thread.sleep(250)
        }
        Log.w("EySnatchTest", "dumpsys accessibility:\n" + shell("dumpsys accessibility").lines().take(80).joinToString("\n"))
        throw AssertionError("the accessibility service did not connect within 40 s")
    }

    private fun focusedWindow(): String =
        shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus=") }.orEmpty().trim()

    private fun shell(command: String): String {
        val fd = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }
}
