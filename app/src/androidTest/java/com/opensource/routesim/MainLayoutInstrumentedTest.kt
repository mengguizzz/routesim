package com.opensource.routesim

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainLayoutInstrumentedTest {
    @Test
    fun privacyAcceptanceCanBeSeededForUiTests() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        application.getSharedPreferences("routesim_ui", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("privacy_policy_v2_accepted", true)
            .commit()
    }

    @Test
    fun settingsPanelMeasuredWithAllControlsVisible() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(application, R.style.Theme_Routesim)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val host = FrameLayout(themed)
            val root = LayoutInflater.from(themed).inflate(R.layout.activity_main, host, false)
            host.addView(root)

            val settings = root.findViewById<View>(R.id.settingsCard)
            settings.visibility = View.VISIBLE
            host.measure(
                MeasureSpec.makeMeasureSpec(2_880, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(1_800, MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 2_880, 1_800)

            assertEquals(View.VISIBLE, settings.visibility)
            assertTrue(settings.measuredHeight > 0)
            assertTrue(root.findViewById<View>(R.id.speedRangeSlider).measuredHeight > 0)
            assertTrue(root.findViewById<View>(R.id.holdRangeSlider).measuredHeight > 0)
            assertTrue(root.findViewById<View>(R.id.etTargetLaps).measuredHeight > 0)
        }
    }
}
