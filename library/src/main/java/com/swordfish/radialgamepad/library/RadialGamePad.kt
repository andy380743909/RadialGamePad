/*
 * Created by Filippo Scognamiglio.
 * Copyright (c) 2020. This file is part of RadialGamePad.
 *
 * RadialGamePad is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RadialGamePad is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RadialGamePad.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:Suppress("unused")

package com.swordfish.radialgamepad.library

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.swordfish.radialgamepad.library.accessibility.AccessibilityBox
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig
import com.swordfish.radialgamepad.library.dials.ButtonDial
import com.swordfish.radialgamepad.library.dials.CrossDial
import com.swordfish.radialgamepad.library.dials.Dial
import com.swordfish.radialgamepad.library.dials.DoubleButtonDial
import com.swordfish.radialgamepad.library.dials.EmptyDial
import com.swordfish.radialgamepad.library.dials.PrimaryButtonsDial
import com.swordfish.radialgamepad.library.dials.StickDial
import com.swordfish.radialgamepad.library.event.Event
import com.swordfish.radialgamepad.library.event.EventsSource
import com.swordfish.radialgamepad.library.event.GestureType
import com.swordfish.radialgamepad.library.haptics.HapticConfig
import com.swordfish.radialgamepad.library.haptics.HapticEngine
import com.swordfish.radialgamepad.library.haptics.actuators.VibrationEffectActuator
import com.swordfish.radialgamepad.library.haptics.actuators.ViewActuator
import com.swordfish.radialgamepad.library.haptics.selectors.AdvancedHapticSelector
import com.swordfish.radialgamepad.library.haptics.selectors.NoEffectHapticSelector
import com.swordfish.radialgamepad.library.haptics.selectors.SimpleHapticSelector
import com.swordfish.radialgamepad.library.math.MathUtils.clamp
import com.swordfish.radialgamepad.library.math.MathUtils.toRadians
import com.swordfish.radialgamepad.library.math.Sector
import com.swordfish.radialgamepad.library.simulation.SimulateKeyDial
import com.swordfish.radialgamepad.library.simulation.SimulateMotionDial
import com.swordfish.radialgamepad.library.touchbound.CircleTouchBound
import com.swordfish.radialgamepad.library.touchbound.SectorTouchBound
import com.swordfish.radialgamepad.library.touchbound.TouchBound
import com.swordfish.radialgamepad.library.utils.Constants
import com.swordfish.radialgamepad.library.utils.MultiTapDetector
import com.swordfish.radialgamepad.library.utils.PaintUtils
import com.swordfish.radialgamepad.library.utils.PaintUtils.scale
import com.swordfish.radialgamepad.library.utils.TouchUtils
import java.lang.ref.WeakReference
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.properties.Delegates
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

@OptIn(DelicateCoroutinesApi::class)
class RadialGamePad @JvmOverloads constructor(
    private val gamePadConfig: RadialGamePadConfig,
    defaultMarginsInDp: Float = 16f,
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), EventsSource {

    private val eventDispatcher = newSingleThreadContext("touch-events")
    private val hapticDispatcher = newSingleThreadContext("haptic-events")
    private val eventsSubject = MutableSharedFlow<Event>()

    private val exploreByTouchHelper = object : ExploreByTouchHelper(this) {

        private fun computeVirtualViews(): Map<Int, AccessibilityBox> {
            return allDials
                .flatMap { it.accessibilityBoxes() }
                .sortedBy { it.rect.top }
                .mapIndexed { index, accessibilityBox -> index to accessibilityBox }
                .toMap()
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            return computeVirtualViews().entries
                .filter { (_, accessibilityBox) -> accessibilityBox.rect.contains(x.roundToInt(), y.roundToInt()) }
                .map { (id, _) -> id }
                .firstOrNull() ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            computeVirtualViews().forEach { (id, _) -> virtualViewIds.add(id) }
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            return false
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val virtualView = computeVirtualViews()[virtualViewId]
            node.setBoundsInParent(virtualView!!.rect)
            node.contentDescription = virtualView.text
        }
    }

    private val marginsInPixel: Int = PaintUtils.convertDpToPixel(defaultMarginsInDp, context).roundToInt()

    private val touchBounds: MutableMap<Dial, TouchBound> = mutableMapOf()
    private var dials: Int = gamePadConfig.sockets
    private var size: Float = 0f
    private var center = PointF(0f, 0f)
    private var positionOnScreen = intArrayOf(0, 0)

    /** Change the horizontal gravity of the gamepad. Use in range [-1, +1] you can move the pad
     *  left or right. This value is not considered when sizing, so the actual shift depends on the
     *  view size.*/
    var gravityX: Float by Delegates.observable(0f) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Change the vertical gravity of the gamepad. Use in range [-1, +1] you can move the pad
     *  up or down. This value is not considered when sizing, so the actual shift depends on the
     *  view size.*/
    var gravityY: Float by Delegates.observable(0f) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Shift the gamepad left or right by this size in pixel. This value is not considered when
     *  sizing and the shift only happens if there is room for it. It is capped so that the
     *  pad is never cropped.*/
    var offsetX: Float by Delegates.observable(0f) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Shift the gamepad top or bottom by this size in pixel. This value is not considered when
     *  sizing and the shift only happens if there is room for it. It is capped so that the
     *  pad is never cropped.*/
    var offsetY: Float by Delegates.observable(0f) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Limit the size of the actual gamepad inside the view.*/
    var primaryDialMaxSizeDp: Float = Float.MAX_VALUE
        set(value) {
            field = value
            requestLayoutAndInvalidate()
        }

    /** Rotate the secondary dials by this value in degrees.*/
    var secondaryDialRotation: Float by Delegates.observable(0f) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Increase the spacing between primary and secondary dials. Use in range [0, 1].*/
    var secondaryDialSpacing: Float = 0.1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            requestLayoutAndInvalidate()
        }

    /** Add spacing at the top. This space will not be considered when drawing and
     *  sizing the gamepad. Touch events in the area will still be forwarded to the View.*/
    var spacingTop: Int by Delegates.observable(0) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Add spacing at the bottom. This space will not be considered when drawing and
     *  sizing the gamepad. Touch events in the area will still be forwarded to the View.*/
    var spacingBottom: Int by Delegates.observable(0) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Add spacing at the left. This space will not be considered when drawing and
     *  sizing the gamepad. Touch events in the area will still be forwarded to the View.*/
    var spacingLeft: Int by Delegates.observable(0) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    /** Add spacing at the right. This space will not be considered when drawing and
     *  sizing the gamepad. Touch events in the area will still be forwarded to the View.*/
    var spacingRight: Int by Delegates.observable(0) { _, _, _ ->
        requestLayoutAndInvalidate()
    }

    private val hapticEngine = createHapticEngine()

    private var primaryDial: Dial
    private var secondaryDials: List<Dial>
    private lateinit var allDials: List<Dial>

    private val tapsDetector: MultiTapDetector = MultiTapDetector(context) { x, y, taps, isConfirmed ->
        if (!isConfirmed) return@MultiTapDetector

        val gestureType = when (taps) {
            0 -> GestureType.FIRST_TOUCH
            1 -> GestureType.SINGLE_TAP
            2 -> GestureType.DOUBLE_TAP
            3 -> GestureType.TRIPLE_TAP
            else -> null
        } ?: return@MultiTapDetector

        val events = mutableListOf<Event>()

        val updated = allDials.map {
            if (touchBounds[it]?.contains(x, y) == true) {
                val relativePosition = TouchUtils.computeRelativePosition(x, y, it.drawingBox())
                it.gesture(relativePosition.x, relativePosition.y, gestureType, events)
            } else {
                false
            }
        }

        if (updated.any { it }) {
            postInvalidate()
        }

        handleEvents(events)
    }

    private fun createHapticEngine(): HapticEngine {
        val actuator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffectActuator(context.applicationContext)
        } else {
            ViewActuator(WeakReference(this))
        }

        val selector = when (gamePadConfig.haptic) {
            HapticConfig.OFF -> NoEffectHapticSelector()
            HapticConfig.PRESS -> SimpleHapticSelector()
            HapticConfig.PRESS_AND_RELEASE -> AdvancedHapticSelector()
        }

        return HapticEngine(selector, actuator)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        primaryDial = buildPrimaryInteractor(gamePadConfig.primaryDial)
        secondaryDials = buildSecondaryInteractors(gamePadConfig.secondaryDials)
        allDials = listOf(primaryDial) + secondaryDials
        ViewCompat.setAccessibilityDelegate(this, exploreByTouchHelper)
    }

    /** Simulate a motion event. It's used in Lemuroid to map events from sensors. */
    fun simulateMotionEvent(id: Int, relativeX: Float, relativeY: Float) {
        val events = mutableListOf<Event>()

        val updated = allDials.filterIsInstance(SimulateMotionDial::class.java)
            .map { it.simulateMotion(id, relativeX, relativeY, events) }
            .any { it }

        if (updated) {
            postInvalidate()
        }

        handleEvents(events)
    }

    /** Programmatically clear motion events associated with the id. */
    fun simulateClearMotionEvent(id: Int) {
        val events = mutableListOf<Event>()

        val updated = allDials.filterIsInstance(SimulateMotionDial::class.java)
            .map { it.clearSimulatedMotion(id, events) }
            .any { it }

        if (updated) {
            postInvalidate()
        }

        handleEvents(events)
    }

    /** Simulate a key event. It's used in Lemuroid to map events from sensors. */
    fun simulateKeyEvent(id: Int, pressed: Boolean) {
        val events = mutableListOf<Event>()

        val updated = allDials.filterIsInstance(SimulateKeyDial::class.java)
            .map { it.simulateKeyPress(id, pressed, events) }
            .any { it }

        if (updated) {
            postInvalidate()
        }

        handleEvents(events)
    }

    /** Simulate a key event. It's used in Lemuroid to map events from sensors. */
    fun simulateClearKeyEvent(id: Int) {
        val events = mutableListOf<Event>()

        val updated = allDials.filterIsInstance(SimulateKeyDial::class.java)
            .map { it.clearSimulateKeyPress(id, events) }
            .any { it }

        if (updated) {
            postInvalidate()
        }

        handleEvents(events)
    }

    private fun handleEvents(events: List<Event>) {
        GlobalScope.launch(hapticDispatcher) {
            hapticEngine.performHapticForEvents(events)
        }
        GlobalScope.launch(eventDispatcher) {
            events.forEach {
                eventsSubject.emit(it)
            }
        }
    }

    private fun buildPrimaryInteractor(configuration: PrimaryDialConfig): Dial {
        return when (configuration) {
            is PrimaryDialConfig.Cross -> CrossDial(
                context,
                configuration.crossConfig,
                configuration.crossConfig.theme ?: gamePadConfig.theme
            )
            is PrimaryDialConfig.Stick -> StickDial(
                context,
                configuration.id,
                configuration.buttonPressId,
                configuration.supportsGestures,
                configuration.contentDescription,
                configuration.theme ?: gamePadConfig.theme
            )
            is PrimaryDialConfig.PrimaryButtons -> PrimaryButtonsDial(
                context,
                configuration.dials,
                configuration.center,
                toRadians(configuration.rotationInDegrees),
                configuration.allowMultiplePressesSingleFinger,
                configuration.theme ?: gamePadConfig.theme
            )
        }
    }

    private fun buildSecondaryInteractors(secondaryDials: List<SecondaryDialConfig>): List<Dial> {
        return secondaryDials.map { config ->
            val secondaryDial = when (config) {
                is SecondaryDialConfig.Stick -> StickDial(
                    context,
                    config.id,
                    config.buttonPressId,
                    config.supportsGestures,
                    config.contentDescription,
                    config.theme ?: gamePadConfig.theme
                )
                is SecondaryDialConfig.SingleButton -> ButtonDial(
                    context,
                    config.buttonConfig,
                    config.theme ?: gamePadConfig.theme
                )
                is SecondaryDialConfig.DoubleButton -> DoubleButtonDial(
                    context,
                    config.buttonConfig,
                    config.theme ?: gamePadConfig.theme
                )
                is SecondaryDialConfig.Empty -> EmptyDial()
                is SecondaryDialConfig.Cross -> CrossDial(
                    context,
                    config.crossConfig,
                    config.crossConfig.theme ?: gamePadConfig.theme
                )
            }
            secondaryDial
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val extendedSize = computeTotalSizeAsSizeMultipliers()

        applyMeasuredDimensions(widthMeasureSpec, heightMeasureSpec, extendedSize)

        val usableWidth = measuredWidth - spacingLeft - spacingRight - 2 * marginsInPixel
        val usableHeight = measuredHeight - spacingTop - spacingBottom - 2 * marginsInPixel

        size = minOf(
            usableWidth / extendedSize.width(),
            usableHeight / extendedSize.height(),
            PaintUtils.convertDpToPixel(primaryDialMaxSizeDp, context) / 2f
        )

        val maxDisplacementX = (usableWidth - size * extendedSize.width()) / 2f
        val maxDisplacementY = (usableHeight - size * extendedSize.height()) / 2f

        val totalDisplacementX = gravityX * maxDisplacementX + offsetX
        val finalOffsetX = clamp(totalDisplacementX, -maxDisplacementX, maxDisplacementX)

        val totalDisplacementY = gravityY * maxDisplacementY + offsetY
        val finalOffsetY = clamp(totalDisplacementY, -maxDisplacementY, maxDisplacementY)

        val baseCenterX = spacingLeft + (measuredWidth - spacingLeft - spacingRight) / 2f
        val baseCenterY = spacingTop + (measuredHeight - spacingTop - spacingBottom) / 2f

        center.x = finalOffsetX + baseCenterX - (extendedSize.left + extendedSize.right) * size * 0.5f
        center.y = finalOffsetY + baseCenterY - (extendedSize.top + extendedSize.bottom) * size * 0.5f

        touchBounds.clear()
        measurePrimaryDial()
        measureSecondaryDials()
    }

    private fun applyMeasuredDimensions(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
        extendedSize: RectF
    ) {
        val (widthMode, width) = extractModeAndDimension(widthMeasureSpec)
        val (heightMode, height) = extractModeAndDimension(heightMeasureSpec)

        val usableWidth = width - spacingLeft - spacingRight - 2 * marginsInPixel
        val usableHeight = height - spacingBottom - spacingTop - 2 * marginsInPixel

        val enforcedMaxSize = PaintUtils.convertDpToPixel(primaryDialMaxSizeDp, context) / 2

        when {
            widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.AT_MOST -> {
                setMeasuredDimension(
                    width,
                    minOf(
                        usableHeight,
                        (usableWidth * extendedSize.height() / extendedSize.width()).roundToInt(),
                        (enforcedMaxSize * extendedSize.height()).roundToInt()
                    ) + spacingBottom + spacingTop + 2 * marginsInPixel
                )
            }
            widthMode == MeasureSpec.AT_MOST && heightMode == MeasureSpec.EXACTLY -> {
                setMeasuredDimension(
                    minOf(
                        usableWidth,
                        (usableHeight * extendedSize.width() / extendedSize.height()).roundToInt(),
                        (enforcedMaxSize * extendedSize.width()).roundToInt()
                    ) + spacingLeft + spacingRight + 2 * marginsInPixel,
                    height
                )
            }
            else -> setMeasuredDimension(width, height)
        }
    }

    private fun extractModeAndDimension(widthMeasureSpec: Int): Pair<Int, Int> {
        return MeasureSpec.getMode(widthMeasureSpec) to MeasureSpec.getSize(widthMeasureSpec)
    }

    /** Different dial configurations cause the view to grow in different directions. This functions
     *  returns a bounding box as multipliers of 'size' that contains the whole view. They are later
     *  used to compute the actual size. */
    private fun computeTotalSizeAsSizeMultipliers(): RectF {
        val allSockets = gamePadConfig.secondaryDials

        val sizes = allSockets.map { config ->
            if (config.avoidClipping) {
                measureSecondaryDialDrawingBoxNoClipping(config)
            } else {
                measureSecondaryDialDrawingBox(config)
            }
        }

        return PaintUtils.mergeRectangles(listOf(RectF(-1f, -1f, 1f, 1f)) + sizes)
    }

    private fun measureSecondaryDials() {
        gamePadConfig.secondaryDials.forEachIndexed { index, config ->
            val (rect, sector) = measureSecondaryDial(config)
            val dial = secondaryDials[index]
            touchBounds[dial] = SectorTouchBound(sector)
            dial.measure(rect, sector)
        }
    }

    private fun measurePrimaryDial() {
        touchBounds[primaryDial] = CircleTouchBound(center, size)
        primaryDial.measure(RectF(center.x - size, center.y - size, center.x + size, center.y + size))
    }

    private fun measureSecondaryDial(config: SecondaryDialConfig): Pair<RectF, Sector> {
        val rect = measureSecondaryDialDrawingBox(config).scale(size)
        rect.offset(center.x, center.y)

        val dialAngle = Constants.PI2 / dials
        val dialSize = DEFAULT_SECONDARY_DIAL_SCALE * size * config.scale
        val finalRotation = computeRotationInRadiansForDial(config)
        val offset = size * DEFAULT_SECONDARY_DIAL_SCALE * (secondaryDialSpacing + computeFinalSpacing(config))

        val sector = Sector(
            PointF(center.x, center.y),
            size + offset,
            size + offset + dialSize * config.scale,
            finalRotation + config.index * dialAngle - dialAngle / 2,
            finalRotation + (config.index + config.spread - 1) * dialAngle + dialAngle / 2
        )

        return rect to sector
    }

    private fun computeRotationInRadiansForDial(config: SecondaryDialConfig): Float {
        return toRadians(config.rotationProcessor.getRotation(secondaryDialRotation))
    }

    private fun computeFinalSpacing(config: SecondaryDialConfig): Float {
        return config.rotationProcessor.getSpacing(config.distance, secondaryDialRotation)
    }

    private fun measureSecondaryDialDrawingBoxNoClipping(config: SecondaryDialConfig): RectF {
        val drawingBoxes = (config.index until (config.index + config.spread))
            .map { measureSecondaryDialDrawingBox(config, it, 1) }

        return PaintUtils.mergeRectangles(drawingBoxes)
    }

    private fun measureSecondaryDialDrawingBox(config: SecondaryDialConfig): RectF {
        return measureSecondaryDialDrawingBox(config, null, null)
    }

    private fun measureSecondaryDialDrawingBox(
        config: SecondaryDialConfig,
        overrideIndex: Int?,
        overrideSpread: Int?
    ): RectF {
        val index = overrideIndex ?: config.index
        val spread = overrideSpread ?: config.spread
        val dialAngle = Constants.PI2 / dials
        val dialSize = DEFAULT_SECONDARY_DIAL_SCALE * config.scale
        val offset = secondaryDialSpacing + computeFinalSpacing(config)
        val distanceToCenter = DEFAULT_SECONDARY_DIAL_SCALE * offset + maxOf(
            0.5f * dialSize / tan(dialAngle * spread / 2f),
            1.0f + dialSize / 2f
        )

        val finalIndex = index + (spread - 1) * 0.5f
        val finalAngle = finalIndex * dialAngle + computeRotationInRadiansForDial(config)

        return RectF(
            (cos(finalAngle) * distanceToCenter - dialSize / 2f),
            (-sin(finalAngle) * distanceToCenter - dialSize / 2f),
            (cos(finalAngle) * distanceToCenter + dialSize / 2f),
            (-sin(finalAngle) * distanceToCenter + dialSize / 2f)
        )
    }

    private fun requestLayoutAndInvalidate() {
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        primaryDial.draw(canvas)

        secondaryDials.forEach {
            it.draw(canvas)
        }
    }

    override fun events(): Flow<Event> {
        return eventsSubject
    }

    fun performHapticFeedback() {
        hapticEngine.performHaptic(HapticEngine.EFFECT_PRESS)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapsDetector.handleEvent(event)

        val fingers = extractFingersPositions(event).toList()

        val trackedFingers = allDials
            .map { it.trackedPointersIds() }
            .reduceRight { a, b -> a.union(b) }

        val events = mutableListOf<Event>()

        val fingersInDial = fingers.groupBy { findInteractorForFinger(it) }

        val updated = allDials.map { dial ->
            val dialUntrackedFingers = fingersInDial.getOrElse(dial) { emptyList() }
                .filter { it.pointerId !in trackedFingers }

            val dialTrackedFingers = fingers
                .filter { it.pointerId in dial.trackedPointersIds() }

            val allFingers = dialUntrackedFingers + dialTrackedFingers

            dial.touch(
                TouchUtils.computeRelativeFingerPosition(allFingers, dial.drawingBox()),
                events
            )
        }

        if (updated.any { it }) {
            postInvalidate()
        }

        handleEvents(events)

        // 判断是否有手指在 dial 的区域内
        val isTouchInDial = fingers.any { finger ->
            allDials.any { dial ->
                touchBounds[dial]?.contains(finger.x, finger.y) == true
            }
        }

        // 如果没有手指在 dial 的区域内，返回 false，让事件继续传递
        if (!isTouchInDial) {
            return false
        } else {
            return true
        }
    }

    private fun findInteractorForFinger(finger: TouchUtils.FingerPosition): Dial? {
        return allDials.firstOrNull { touchBounds[it]?.contains(finger.x, finger.y) ?: false }
    }

    private fun extractFingersPositions(event: MotionEvent): Sequence<TouchUtils.FingerPosition> {
        return if (gamePadConfig.preferScreenTouchCoordinates && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getLocationOnScreen(positionOnScreen)
            TouchUtils.extractRawFingersPositions(event, positionOnScreen[0], positionOnScreen[1])
        } else {
            TouchUtils.extractFingersPositions(event)
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (exploreByTouchHelper.dispatchHoverEvent(event)) {
            return true
        }
        return super.dispatchHoverEvent(event)
    }

    companion object {
        const val DEFAULT_SECONDARY_DIAL_SCALE = 0.75f
    }
}
