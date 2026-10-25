package com.opensource.routesim.ui

import android.view.GestureDetector
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot
import kotlin.math.log2

/**
 * Edit-mode map gesture layer:
 * - one finger appends a continuous route stroke;
 * - two fingers pan and pinch-zoom;
 * - a second finger cancels the partial stroke so panning never leaves marks.
 */
class RouteDrawingOverlay(
    private val points: MutableList<GeoPoint>,
    private val onRouteChanged: () -> Unit,
) : Overlay() {
    var drawingEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) cancelStroke()
        }

    private val strokes = mutableListOf<IntRange>()
    private var currentScale = 1f
    private var gesture = Gesture.NONE
    private var strokeStartIndex = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var transformFocusX = 0f
    private var transformFocusY = 0f
    private var transformSpan = 1f
    private lateinit var tapDetector: GestureDetector

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        if (!drawingEnabled) {
            return false
        }
        if (!::tapDetector.isInitialized) {
            tapDetector = GestureDetector(
                mapView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        mapView.controller.zoomToFixing(
                            (mapView.zoomLevelDouble + 1.0).coerceAtMost(mapView.maxZoomLevel),
                            e.x.toInt(),
                            e.y.toInt(),
                        )
                        return true
                    }
                },
            )
        }
        tapDetector.onTouchEvent(event)
        currentScale = mapView.resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginStroke(mapView, event.x, event.y)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    cancelStroke()
                    beginTransform(mapView, event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.TRANSFORM && event.pointerCount >= 2) {
                    updateTransform(mapView, event)
                } else if (gesture == Gesture.DRAW && event.pointerCount == 1) {
                    appendPoint(mapView, event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A lifted finger invalidates the old focus/span baseline.
                // Ignore the remaining finger until the whole gesture is over.
                gesture = Gesture.NONE
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.DRAW) {
                    appendPoint(mapView, event.x, event.y)
                    finishStroke()
                }
                gesture = Gesture.NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.DRAW) {
                    cancelStroke()
                }
                gesture = Gesture.NONE
                return true
            }
        }
        return true
    }

    fun clear() {
        points.clear()
        strokes.clear()
        gesture = Gesture.NONE
        onRouteChanged()
    }

    fun undoLastStroke(): Boolean {
        val range = strokes.removeLastOrNull() ?: return false
        val start = range.first.coerceIn(0, points.size)
        val endExclusive = (range.last + 1).coerceIn(start, points.size)
        if (endExclusive > start) {
            points.subList(start, endExclusive).clear()
        }
        onRouteChanged()
        return true
    }

    private fun beginStroke(mapView: MapView, x: Float, y: Float) {
        cancelStroke()
        strokeStartIndex = points.size
        gesture = Gesture.DRAW
        lastTouchX = x
        lastTouchY = y
        appendPoint(mapView, x, y, force = true)
    }

    private fun appendPoint(mapView: MapView, x: Float, y: Float, force: Boolean = false) {
        if (!force && hypot(x - lastTouchX, y - lastTouchY) < 5f * currentScale) {
            return
        }
        val point = mapView.projection.fromPixels(x.toInt(), y.toInt()) as? GeoPoint ?: return
        points += point
        lastTouchX = x
        lastTouchY = y
        onRouteChanged()
    }

    private fun finishStroke() {
        val addedCount = points.size - strokeStartIndex
        if (addedCount >= 2) {
            strokes += strokeStartIndex until points.size
        } else if (addedCount == 1) {
            points.removeAt(points.lastIndex)
            onRouteChanged()
        }
        gesture = Gesture.NONE
    }

    private fun cancelStroke() {
        if (gesture == Gesture.DRAW && points.size > strokeStartIndex) {
            points.subList(strokeStartIndex, points.size).clear()
            onRouteChanged()
        }
        gesture = Gesture.NONE
    }

    private fun beginTransform(mapView: MapView, event: MotionEvent) {
        if (event.pointerCount < 2) return
        gesture = Gesture.TRANSFORM
        transformFocusX = (event.getX(0) + event.getX(1)) / 2f
        transformFocusY = (event.getY(0) + event.getY(1)) / 2f
        transformSpan = distance(event).coerceAtLeast(1f)
    }

    private fun updateTransform(mapView: MapView, event: MotionEvent) {
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        val newSpan = distance(event).coerceAtLeast(1f)
        val scale = newSpan / transformSpan
        if (scale.isFinite() && scale > 0f) {
            // Short pinches should move the map enough to feel responsive,
            // while long gestures still follow the fingers predictably.
            val zoom = (mapView.zoomLevelDouble + log2(scale) * 1.65).coerceIn(
                mapView.minZoomLevel,
                mapView.maxZoomLevel,
            )
            mapView.controller.zoomToFixing(zoom, focusX.toInt(), focusY.toInt())
        }

        val panGain = 1.35f
        mapView.scrollBy(
            ((transformFocusX - focusX) * panGain).toInt(),
            ((transformFocusY - focusY) * panGain).toInt(),
        )
        mapView.invalidate()
        transformFocusX = focusX
        transformFocusY = focusY
        transformSpan = newSpan
    }

    private fun distance(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    private enum class Gesture {
        NONE,
        DRAW,
        TRANSFORM,
    }
}
