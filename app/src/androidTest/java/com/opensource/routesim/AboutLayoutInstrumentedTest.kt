package com.opensource.routesim

import android.content.Context
import android.content.res.Configuration
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
class AboutLayoutInstrumentedTest {
    @Test
    fun aboutPageAndThemeSelectorMeasureCorrectly() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(application, R.style.Theme_Routesim)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val host = FrameLayout(themed)
            val root = LayoutInflater.from(themed).inflate(R.layout.content_about, host, false)
            host.addView(root)
            host.measure(
                MeasureSpec.makeMeasureSpec(2_880, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(1_800, MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 2_880, 1_800)

            assertTrue(root.findViewById<View>(R.id.btnGithub).measuredHeight > 0)
            assertTrue(root.findViewById<View>(R.id.themeGroup).measuredHeight > 0)
            assertTrue(root.findViewById<View>(R.id.btnThemeDark).measuredHeight > 0)
        }
    }

    @Test
    fun nightThemeUsesDarkSurfaceAndLightText() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(application.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val nightContext = application.createConfigurationContext(configuration)
        assertEquals(0xFF101A18.toInt(), nightContext.getColor(R.color.route_surface))
        assertEquals(0xFFEDF8F4.toInt(), nightContext.getColor(R.color.route_ink))
    }

    @Test
    fun privacyConsentLayoutMeasuresAndStartsDisabled() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(application, R.style.Theme_Routesim)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val host = FrameLayout(themed)
            val root = LayoutInflater.from(themed).inflate(
                R.layout.dialog_privacy_consent,
                host,
                false,
            )
            host.addView(root)
            host.measure(
                MeasureSpec.makeMeasureSpec(1_200, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(1_200, MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 1_200, 1_200)

            assertTrue(root.findViewById<View>(R.id.privacyScroll).measuredHeight > 0)
            assertTrue(root.findViewById<View>(R.id.etPrivacyConfirm).measuredHeight > 0)
            assertEquals(false, root.findViewById<View>(R.id.btnPrivacyAgree).isEnabled)
        }
    }
}
