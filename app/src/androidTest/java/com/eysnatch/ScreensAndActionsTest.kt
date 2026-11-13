package com.eysnatch

import android.app.Activity
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.eysnatch.core.PhoneAction
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreensAndActionsTest {

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

    /** Only meaningful when the accessibility service has been switched on (the CI script does that). */
    @Test
    fun accessibilityServiceCanPressHome() {
        val svc = waitForService()
        assumeNotNull(svc)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
        val svc = waitForService()
        assumeNotNull(svc)
        ActivityScenario.launch(SettingsActivity::class.java).use {
            Thread.sleep(1500)
            for (a in listOf(PhoneAction.SCROLL_DOWN, PhoneAction.SCROLL_UP, PhoneAction.SWIPE_LEFT, PhoneAction.SWIPE_RIGHT, PhoneAction.FORWARD)) {
                svc!!.perform(a, "test")
                Thread.sleep(700)
            }
            Log.i("EySnatchTest", "SWIPES TEST RAN")
        }
    }

    private fun waitForService(): EySnatchAccessibilityService? {
        repeat(40) {
            EySnatchAccessibilityService.instance?.let { return it }
            Thread.sleep(250)
        }
        return null
    }
}
