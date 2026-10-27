package com.monib.receivercontrol.controller.widgets


import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
// Adapted from Outofcontext Team's Android TV Remote DpadView (MIT License).

/**
 * DpadView — Material You 3 Expressive D-Pad
 *
 * A clean, minimal D-Pad with:
 *  • Organic petal-shaped directional zones
 *  • Ripple press feedback per zone
 *  • Center OK button with subtle bounce
 *  • Adaptive color from M3 color scheme
 *  • Haptic on every press
 *
 * Usage in XML:
 *   <com.out.androidtvremote.views.DpadView
 *       android:id="@+id/dpad"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent"
 *       android:clickable="true"
 *       android:focusable="true" />
 */
class ReferenceDpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Listener ────────────────────────────────────────────────────────────
    interface DpadListener {
        fun onUp()
        fun onDown()
        fun onLeft()
        fun onRight()
        fun onCenter()
    }

    var listener: DpadListener? = null

    // ── Colors (resolved lazily after attach) ────────────────────────────────
    private var colorSurface = Color.parseColor("#1C1B1F")
    private var colorSurfaceContainer = Color.parseColor("#211F26")
    private var colorOnSurface = Color.parseColor("#E6E1E5")
    private var colorPrimary = Color.parseColor("#D0BCFF")
    private var colorPrimaryContainer = Color.parseColor("#4F378B")
    private var colorOnPrimaryContainer = Color.parseColor("#EADDFF")
    private var colorOutlineVariant = Color.parseColor("#49454F")

    // ── Geometry ─────────────────────────────────────────────────────────────
    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f      // radius of the whole d-pad disc
    private var innerR = 0f      // radius of the center OK circle
    private var arrowR = 0f      // where arrow icons sit

    // ── Paint set ────────────────────────────────────────────────────────────
    private val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val okTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── State ────────────────────────────────────────────────────────────────
    enum class Zone { NONE, UP, DOWN, LEFT, RIGHT, CENTER }

    private var pressedZone = Zone.NONE
    private var rippleZone = Zone.NONE
    private var rippleRadius = 0f
    private var rippleAlpha = 0f
    private var rippleCx = 0f
    private var rippleCy = 0f

    // Center scale for bounce
    private var centerScale = 1f

    // Press alpha for each sector
    private val sectorAlpha = FloatArray(4) { 0f }   // UP DOWN LEFT RIGHT

    // ── Paths ────────────────────────────────────────────────────────────────
    private val diskPath = Path()
    private val sectorPaths = Array(4) { Path() }
    private val centerPath = Path()

    // ── Reusable rect ────────────────────────────────────────────────────────
    private val oval = RectF()

    // ── Arrow drawing helper vars ─────────────────────────────────────────────
    private val arrowPath = Path()

    fun setPalette(
        surface: Int,
        surfaceContainer: Int,
        onSurface: Int,
        primary: Int,
        primaryContainer: Int,
        onPrimaryContainer: Int,
        outlineVariant: Int
    ) {
        colorSurface = surface
        colorSurfaceContainer = surfaceContainer
        colorOnSurface = onSurface
        colorPrimary = primary
        colorPrimaryContainer = primaryContainer
        colorOnPrimaryContainer = onPrimaryContainer
        colorOutlineVariant = outlineVariant
        setupPaints()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupPaints()
    }

    private fun setupPaints() {
        diskPaint.apply {
            color = colorSurfaceContainer
            style = Paint.Style.FILL
        }
        outerRingPaint.apply {
            color = colorOutlineVariant
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dp
        }
        sectorPressedPaint.apply {
            color = colorPrimary
            style = Paint.Style.FILL
        }
        centerPaint.apply {
            color = colorSurface
            style = Paint.Style.FILL
        }
        centerPressedPaint.apply {
            color = colorPrimaryContainer
            style = Paint.Style.FILL
        }
        dividerPaint.apply {
            color = colorOutlineVariant
            style = Paint.Style.STROKE
            strokeWidth = 1f.dp
        }
        arrowPaint.apply {
            color = colorOnSurface
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        okTextPaint.apply {
            color = colorOnSurface
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        ripplePaint.apply {
            color = colorPrimary
            style = Paint.Style.FILL
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val size = minOf(w, h).toFloat()
        cx = w / 2f
        cy = h / 2f
        outerR = size / 2f * 0.92f
        innerR = outerR * 0.30f
        arrowR = outerR * 0.65f

        buildPaths()
        okTextPaint.textSize = innerR * 0.55f
    }

    private fun buildPaths() {
        // Main disc
        diskPath.reset()
        diskPath.addCircle(cx, cy, outerR, Path.Direction.CW)

        // 4 sector clip paths (wedge = pie slice minus center)
        // UP   = -135° to -45°
        // RIGHT=  -45° to  45°
        // DOWN =   45° to 135°
        // LEFT =  135° to 225°
        val angles = arrayOf(
            floatArrayOf(-135f, 90f),  // UP
            floatArrayOf(-45f, 90f),   // RIGHT
            floatArrayOf(45f, 90f),    // DOWN
            floatArrayOf(135f, 90f)    // LEFT
        )
        sectorPaths.forEachIndexed { i, path ->
            path.reset()
            oval.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
            path.moveTo(cx, cy)
            path.arcTo(oval, angles[i][0], angles[i][1])
            path.close()
            // Cut center hole
            path.addCircle(cx, cy, innerR + 1.dp, Path.Direction.CCW)
        }

        // Center circle
        centerPath.reset()
        centerPath.addCircle(cx, cy, innerR, Path.Direction.CW)
    }

    // ── Drawing ──────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Base disc
        canvas.drawCircle(cx, cy, outerR, diskPaint)

        // 2. Pressed sectors with alpha
        val zoneToIndex = mapOf(Zone.UP to 0, Zone.RIGHT to 1, Zone.DOWN to 2, Zone.LEFT to 3)
        zoneToIndex.forEach { (zone, i) ->
            if (sectorAlpha[i] > 0f) {
                sectorPressedPaint.alpha = (sectorAlpha[i] * 80).toInt()
                canvas.save()
                canvas.clipPath(sectorPaths[i])
                canvas.drawCircle(cx, cy, outerR, sectorPressedPaint)
                canvas.restore()
                sectorPressedPaint.alpha = 255
            }
        }

        // 3. Cross dividers (thin lines forming the + shape)
        drawDividers(canvas)

        // 4. Outer ring stroke
        canvas.drawCircle(cx, cy, outerR, outerRingPaint)

        // 5. Center button
        val centerFill = if (pressedZone == Zone.CENTER) centerPressedPaint else centerPaint
        canvas.save()
        canvas.scale(centerScale, centerScale, cx, cy)
        canvas.drawCircle(cx, cy, innerR, centerFill)
        // Center ring
        outerRingPaint.color = colorOutlineVariant
        canvas.drawCircle(cx, cy, innerR, outerRingPaint)
        outerRingPaint.color = colorOutlineVariant

        // OK text
        okTextPaint.color = if (pressedZone == Zone.CENTER) colorOnPrimaryContainer else colorOnSurface
        canvas.drawText("OK", cx, cy + okTextPaint.textSize * 0.38f, okTextPaint)
        canvas.restore()

        // 6. Arrows
        drawArrow(canvas, Zone.UP)
        drawArrow(canvas, Zone.DOWN)
        drawArrow(canvas, Zone.LEFT)
        drawArrow(canvas, Zone.RIGHT)

        // 7. Ripple
        if (rippleAlpha > 0f) {
            ripplePaint.alpha = (rippleAlpha * 60).toInt()
            canvas.drawCircle(rippleCx, rippleCy, rippleRadius, ripplePaint)
        }
    }

    private fun drawDividers(canvas: Canvas) {
        // Vertical line (top arc → bottom arc, interrupted by center)
        val gap = innerR + 2.dp
        canvas.drawLine(cx, cy - outerR, cx, cy - gap, dividerPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + outerR, dividerPaint)
        // Horizontal line
        canvas.drawLine(cx - outerR, cy, cx - gap, cy, dividerPaint)
        canvas.drawLine(cx + gap, cy, cx + outerR, cy, dividerPaint)
    }

    private fun drawArrow(canvas: Canvas, zone: Zone) {
        val idx = when (zone) { Zone.UP -> 0; Zone.RIGHT -> 1; Zone.DOWN -> 2; else -> 3 }
        val pressed = sectorAlpha[idx] > 0.5f

        arrowPaint.color = if (pressed) colorPrimary else colorOnSurface
        arrowPaint.alpha = if (pressed) 255 else (255 * 0.7f).toInt()
        arrowPaint.style = Paint.Style.FILL

        val size = outerR * 0.11f   // arrowhead half-size
        val (ax, ay) = when (zone) {
            Zone.UP    -> Pair(cx, cy - arrowR)
            Zone.DOWN  -> Pair(cx, cy + arrowR)
            Zone.LEFT  -> Pair(cx - arrowR, cy)
            Zone.RIGHT -> Pair(cx + arrowR, cy)
            else -> return
        }

        arrowPath.reset()
        when (zone) {
            Zone.UP -> {
                arrowPath.moveTo(ax, ay - size)
                arrowPath.lineTo(ax + size * 1.2f, ay + size * 0.7f)
                arrowPath.lineTo(ax - size * 1.2f, ay + size * 0.7f)
            }
            Zone.DOWN -> {
                arrowPath.moveTo(ax, ay + size)
                arrowPath.lineTo(ax + size * 1.2f, ay - size * 0.7f)
                arrowPath.lineTo(ax - size * 1.2f, ay - size * 0.7f)
            }
            Zone.LEFT -> {
                arrowPath.moveTo(ax - size, ay)
                arrowPath.lineTo(ax + size * 0.7f, ay - size * 1.2f)
                arrowPath.lineTo(ax + size * 0.7f, ay + size * 1.2f)
            }
            Zone.RIGHT -> {
                arrowPath.moveTo(ax + size, ay)
                arrowPath.lineTo(ax - size * 0.7f, ay - size * 1.2f)
                arrowPath.lineTo(ax - size * 0.7f, ay + size * 1.2f)
            }
            else -> return
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    // ── Touch handling ────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = event.x
        val ty = event.y
        val zone = hitZone(tx, ty)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedZone = zone
                animatePress(zone, true, tx, ty)
                if (zone != Zone.NONE) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (zone != pressedZone) {
                    animatePress(pressedZone, false, tx, ty)
                    pressedZone = zone
                    animatePress(zone, true, tx, ty)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    fireAction(pressedZone)
                    launchRipple(pressedZone, tx, ty)
                }
                animatePress(pressedZone, false, tx, ty)
                pressedZone = Zone.NONE
            }
        }
        invalidate()
        return true
    }

    private fun hitZone(x: Float, y: Float): Zone {
        val dx = x - cx
        val dy = y - cy
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist > outerR) return Zone.NONE
        if (dist < innerR) return Zone.CENTER
        // Quadrant by angle
        val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return when {
            angle in -135f..-45f -> Zone.UP
            angle in -45f..45f   -> Zone.RIGHT
            angle in 45f..135f   -> Zone.DOWN
            else                  -> Zone.LEFT
        }
    }

    private fun fireAction(zone: Zone) {
        when (zone) {
            Zone.UP     -> listener?.onUp()
            Zone.DOWN   -> listener?.onDown()
            Zone.LEFT   -> listener?.onLeft()
            Zone.RIGHT  -> listener?.onRight()
            Zone.CENTER -> listener?.onCenter()
            Zone.NONE   -> {}
        }
    }

    // ── Animation helpers ─────────────────────────────────────────────────────
    private fun animatePress(zone: Zone, down: Boolean, tx: Float, ty: Float) {
        when (zone) {
            Zone.CENTER -> {
                val target = if (down) 0.88f else 1f
                val interp = if (down) AccelerateDecelerateInterpolator() else OvershootInterpolator(3f)
                ObjectAnimator.ofFloat(this, "centerScale", centerScale, target).apply {
                    duration = if (down) 80 else 300
                    interpolator = interp
                    addUpdateListener { invalidate() }
                    start()
                }
            }
            Zone.UP, Zone.DOWN, Zone.LEFT, Zone.RIGHT -> {
                val idx = zoneIdx(zone) ?: return
                ValueAnimator.ofFloat(sectorAlpha[idx], if (down) 1f else 0f).apply {
                    duration = if (down) 60 else 200
                    addUpdateListener {
                        sectorAlpha[idx] = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            else -> {}
        }
    }

    private fun launchRipple(zone: Zone, tx: Float, ty: Float) {
        if (zone == Zone.NONE) return
        rippleCx = if (zone == Zone.CENTER) cx else tx
        rippleCy = if (zone == Zone.CENTER) cy else ty
        rippleZone = zone

        val maxR = outerR * 0.45f
        val alphaAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 450
            addUpdateListener { rippleAlpha = it.animatedValue as Float; invalidate() }
        }
        val radiusAnim = ValueAnimator.ofFloat(0f, maxR).apply {
            duration = 450
            addUpdateListener { rippleRadius = it.animatedValue as Float }
        }
        AnimatorSet().apply {
            playTogether(alphaAnim, radiusAnim)
            start()
        }
    }

    // property for ObjectAnimator
    private fun setCenterScale(v: Float) { centerScale = v; invalidate() }
    private fun getCenterScale() = centerScale

    private fun zoneIdx(zone: Zone) = when (zone) {
        Zone.UP -> 0; Zone.RIGHT -> 1; Zone.DOWN -> 2; Zone.LEFT -> 3; else -> null
    }

    // ── dp helper ────────────────────────────────────────────────────────────
    private val Float.dp get() = this * resources.displayMetrics.density
    private val Int.dp get() = this.toFloat() * resources.displayMetrics.density
}
