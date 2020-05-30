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

package com.swordfish.radialgamepad.library

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig
import com.swordfish.radialgamepad.library.dials.*
import com.swordfish.radialgamepad.library.event.Event
import com.swordfish.radialgamepad.library.event.EventsSource
import com.swordfish.radialgamepad.library.paint.BasePaint
import com.swordfish.radialgamepad.library.touchbound.CircleTouchBound
import com.swordfish.radialgamepad.library.touchbound.SectorTouchBound
import com.swordfish.radialgamepad.library.touchbound.TouchBound
import com.swordfish.radialgamepad.library.utils.Constants
import com.swordfish.radialgamepad.library.utils.MathUtils
import com.swordfish.radialgamepad.library.utils.TouchUtils
import io.reactivex.Observable
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class RadialGamePad @JvmOverloads constructor(
    private val gamePadConfig: RadialGamePadConfig,
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), EventsSource {

    private var dials: Int = 0
    private var size: Float = 0f
    private var center = PointF(0f, 0f)

    private lateinit var primaryInteractor: DialInteractor
    private lateinit var secondaryInteractors: Map<Int, DialInteractor>

    private val paint = BasePaint()

    init {
        dials = gamePadConfig.sockets
        initializePrimaryInteractor(gamePadConfig.primaryDial)
        initializeSecondaryInteractors(gamePadConfig.secondaryDials)
    }

    private fun initializePrimaryInteractor(configuration: PrimaryDialConfig) {
        val primaryDial = when (configuration) {
            is PrimaryDialConfig.Cross -> CrossDial(
                context,
                configuration.motionId,
                configuration.rightDrawableId ?: R.drawable.direction_right_normal,
                configuration.rightDrawableId ?: R.drawable.direction_right_pressed,
                configuration.theme ?: gamePadConfig.theme
            )
            is PrimaryDialConfig.Stick -> StickDial(
                configuration.motionId,
                configuration.theme ?: gamePadConfig.theme
            )
            is PrimaryDialConfig.PrimaryButtons -> PrimaryButtonsDial(
                context,
                configuration.dials,
                configuration.center,
                MathUtils.toRadians(configuration.rotationInDegrees),
                configuration.theme ?: gamePadConfig.theme
            )
        }
        primaryInteractor = DialInteractor(primaryDial)
    }

    private fun initializeSecondaryInteractors(secondaryDials: List<SecondaryDialConfig>) {
        secondaryInteractors = secondaryDials.map { config ->
            val secondaryDial = when (config) {
                is SecondaryDialConfig.Stick -> StickDial(
                    config.motionId,
                    config.theme ?: gamePadConfig.theme
                )
                is SecondaryDialConfig.SingleButton -> ButtonDial(
                    context,
                    config.buttonConfig,
                    config.theme ?: gamePadConfig.theme
                )
                is SecondaryDialConfig.Empty -> EmptyDial()
                is SecondaryDialConfig.Cross -> CrossDial(
                    context,
                    config.motionId,
                    config.rightDrawableId ?: R.drawable.direction_right_normal,
                    config.rightDrawableId ?: R.drawable.direction_right_pressed,
                    config.theme ?: gamePadConfig.theme
                )
            }
            config.index to DialInteractor(secondaryDial)
        }.toMap()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val extendedSize = getExtendedSize()

        applyMeasuredDimensions(widthMeasureSpec, heightMeasureSpec, extendedSize)

        size = minOf(measuredWidth / extendedSize.width(), measuredHeight / extendedSize.height()) * 0.9f

        center.x = measuredWidth / 2f - (extendedSize.left + extendedSize.right) * size * 0.5f
        center.y = measuredHeight / 2f + (extendedSize.top + extendedSize.bottom) * size * 0.5f

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

        when {
            widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.AT_MOST -> {
                setMeasuredDimension(
                    width,
                    minOf(
                        height,
                        (width * extendedSize.height() / extendedSize.width()).roundToInt()
                    )
                )
            }
            widthMode == MeasureSpec.AT_MOST && heightMode == MeasureSpec.EXACTLY -> {
                setMeasuredDimension(
                    minOf(
                        width,
                        (height * extendedSize.width() / extendedSize.height()).roundToInt()
                    ), height
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
    private fun getExtendedSize(): RectF {
        val allSockets = gamePadConfig.secondaryDials
        val socketAngle = Constants.PI2 / gamePadConfig.sockets

        val sizes = allSockets
            .flatMap { socket -> (0 until socket.spread).map { socket.index + it to socket.scale } }
            .map { (index, scale) -> (1 + scale) * cos(index * socketAngle) to (1 + scale) * sin(index * socketAngle) }

        val minX = minOf(sizes.map { it.first }.min() ?: -1f, -1f)
        val minY = minOf(sizes.map { it.second }.min() ?: -1f, -1f)

        val maxX = maxOf(sizes.map { it.first }.max() ?: 1f, 1f)
        val maxY = maxOf(sizes.map { it.second }.max() ?: 1f, 1f)

        return RectF(minX, minY, maxX, maxY)
    }

    private fun measureSecondaryDials() {
        gamePadConfig.secondaryDials.forEach { config ->
            val (rect, bounds) = measureSecondaryDial(config)
            secondaryInteractors[config.index]?.touchBound = bounds
            secondaryInteractors[config.index]?.measure(rect)
        }
    }

    private fun measurePrimaryDial() {
        primaryInteractor.measure(RectF(center.x - size, center.y - size, center.x + size, center.y + size))
        primaryInteractor.touchBound = CircleTouchBound(center, size)
    }

    private fun measureSecondaryDial(config: SecondaryDialConfig): Pair<RectF, TouchBound> {
        val dialAngle = Constants.PI2 / dials
        val distanceToCenter = size + (config.scale * size / 2)
        val dialSize = minOf(2.0f * distanceToCenter * sin(Math.PI / dials).toFloat(), size) * config.scale

        val index = config.index + (config.spread - 1) * 0.5f

        val rect = RectF(
            center.x + (cos(index * dialAngle) * distanceToCenter - dialSize / 2f),
            center.y + (-sin(index * dialAngle) * distanceToCenter - dialSize / 2f),
            center.x + (cos(index * dialAngle) * distanceToCenter + dialSize / 2f),
            center.y + (-sin(index * dialAngle) * distanceToCenter + dialSize / 2f)
        )

        val touchBound = SectorTouchBound(
            PointF(center.x, center.y),
            size,
            size + dialSize * config.scale,
            config.index * dialAngle - dialAngle / 2,
            (config.index + config.spread - 1) * dialAngle + dialAngle / 2
        )

        return rect to touchBound
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        paint.color = gamePadConfig.theme.primaryDialBackground
        canvas.drawCircle(center.x, center.y, size, paint)

        paint.color = gamePadConfig.theme.secondaryDialBackground
        canvas.drawCircle(center.x, center.y, size * 2, paint)

        primaryInteractor.draw(canvas)

        secondaryInteractors.values.forEach {
            it.draw(canvas)
        }
    }

    override fun events(): Observable<Event> {
        val allEvents = allDials().map { it.events() }
        return Observable.merge(allEvents)
            .doOnNext {
                if (gamePadConfig.haptic && it.haptic) performHapticFeedback()
            }
    }

    private fun performHapticFeedback() {
        val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, flags)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val fingers = TouchUtils.extractFingerPositions(event)

        val trackedFingers = allDials().mapNotNull { it.trackedPointerId() }

        val updated = allInteractors().map { dial ->
            forwardTouchToDial(dial, fingers, trackedFingers)
        }

        if (updated.any { it }) {
            postInvalidate()
        }

        return true
    }

    private fun forwardTouchToDial(
        dial: DialInteractor,
        fingers: List<TouchUtils.FingerPosition>,
        trackedFingers: List<Int>
    ): Boolean {
        return if (dial.trackedPointerId() != null) {
            dial.touch(fingers.filter { it.pointerId == dial.dial.trackedPointerId() })
        } else {
            dial.touch(fingers.filter { it.pointerId !in trackedFingers })
        }
    }

    private fun allDials(): List<Dial> = allInteractors().map { it.dial }

    private fun allInteractors(): List<DialInteractor> =
        listOf(primaryInteractor) + secondaryInteractors.values
}