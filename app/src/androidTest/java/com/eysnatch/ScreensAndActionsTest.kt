package com.eysnatch

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.eysnatch.core.PhoneAction
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensAndActionsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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
        assertNotNull("the accessibility service did not connect", svc)
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            svc!!.perform(PhoneAction.HOME, "test")
            Thread.sleep(2500)
            Log.i("EySnatchTest", "ACTIONS TEST RAN, foreground=${device.currentPackageName}")
            assertNotEquals("Home did not leave the app", "com.eysnatch", device.currentPackageName)
        }
    }

    @Test
    fun accessibilityServiceAcceptsSwipesAndScrolls() {
        val svc = switchOnAccessibilityService()
        assertNotNull("the accessibility service did not connect", svc)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            Thread.sleep(1500)
            val actions = listOf(
                PhoneAction.SCROLL_DOWN, PhoneAction.SCROLL_UP, PhoneAction.SWIPE_LEFT,
                PhoneAction.SWIPE_RIGHT, PhoneAction.FORWARD, PhoneAction.BACKWARD,
            )
            for (a in actions) {
                svc!!.perform(a, "test")
                Thread.sleep(700)
            }
            Log.i("EySnatchTest", "SWIPES TEST RAN")
        }
    }

    /** Enables the service the way a user would (secure setting) and waits until Android connects it. */
    private fun switchOnAccessibilityService(): EySnatchAccessibilityService? {
        EySnatchAccessibilityService.instance?.let { return it }
        val me = "${instrumentation.targetContext.packageName}/${EySnatchAccessibilityService::class.java.name}"
        shell("settings put secure enabled_accessibility_services $me")
        shell("settings put secure accessibility_enabled 1")
        repeat(160) {
            EySnatchAccessibilityService.instance?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun shell(command: String) {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
