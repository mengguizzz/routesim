package com.opensource.routesim

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opensource.routesim.ui.RouteDrawingOverlay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@RunWith(AndroidJUnit4::class)
class RouteDrawingOverlayInstrumentedTest {
    @Test
    fun twoFingerParallelDragPansAndDoesNotDraw() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(application, R.style.Theme_Routesim)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Configuration.getInstance().load(
                themed,
                themed.getSharedPreferences("osmdroid_test", Context.MODE_PRIVATE),
            )
            val map = MapView(themed).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setUseDataConnection(false)
                setMultiTouchControls(false)
                controller.setCenter(GeoPoint(31.75, 117.16))
                controller.setZoom(16.0)
                layout(0, 0, 1_200, 800)
            }
            val points = mutableListOf<GeoPoint>()
            map.overlays.add(RouteDrawingOverlay(points) {})
            val centerBefore = map.mapCenter

            val down = event(MotionEvent.ACTION_DOWN, floatArrayOf(420f), floatArrayOf(380f), 0L)
            map.dispatchTouchEvent(down)
            down.recycle()

            val pointerDown = event(
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(420f, 700f),
                floatArrayOf(380f, 380f),
                20L,
            )
            map.dispatchTouchEvent(pointerDown)
            pointerDown.recycle()

            val move = event(
                MotionEvent.ACTION_MOVE,
                floatArrayOf(620f, 900f),
                floatArrayOf(440f, 440f),
                40L,
            )
            map.dispatchTouchEvent(move)
            move.recycle()

            val up = event(MotionEvent.ACTION_UP, floatArrayOf(620f), floatArrayOf(440f), 60L)
            map.dispatchTouchEvent(up)
            up.recycle()

            val centerAfter = map.mapCenter
            assertTrue(points.isEmpty())
            val movedDegrees = kotlin.math.abs(centerAfter.latitude - centerBefore.latitude) +
                kotlin.math.abs(centerAfter.longitude - centerBefore.longitude)
            assertTrue(movedDegrees > 0.0001)
            map.onDetach()
        }
    }

    private fun event(
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        eventTime: Long,
    ): MotionEvent {
        val pointers = Array(xs.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(xs.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                y = ys[index]
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            0L,
            eventTime,
            action,
            xs.size,
            pointers,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
    }
}
