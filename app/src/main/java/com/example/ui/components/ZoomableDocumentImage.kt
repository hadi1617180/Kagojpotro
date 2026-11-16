package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.ScanFilter
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * High-resolution zoomable document image viewer supporting:
 * - Double-tap to zoom in (2.8x focused on tap point) and double-tap again to reset to 1.0x
 * - Two-finger pinch-to-zoom (1.0x .. 5.0x) and pan
 * - Simultaneous 1-finger Smart Eraser brushing + 2-finger pinch-to-zoom & pan when [isEraserActive] is true
 * - Live transparent brush outline while brushing or adjusting the brush size slider
 * - Zero-glitch 90-degree clockwise spin + aspect-fit scale interpolation whenever [rotationDegrees] changes.
 */
@Composable
fun ZoomableDocumentImage(
    bitmap: Bitmap,
    contentDescription: String?,
    rotationDegrees: Int,
    resetKey: Any? = null,
    onZoomStateChanged: ((Boolean) -> Unit)? = null,
    isEraserActive: Boolean = false,
    normalizedBrushRadius: Float = 0.024f,
    isAdjustingBrushSize: Boolean = false,
    brushPreviewTouchOffset: Offset? = null,
    accentColor: Color = Color(0xFF38BDF8),
    onEraseStrokeFinished: ((List<Offset>, Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }

    var activeStrokeNormPoints by remember(resetKey, isEraserActive) {
        mutableStateOf<List<Offset>>(emptyList())
    }
    var activeFingerScreenPos by remember(resetKey, isEraserActive) {
        mutableStateOf<Offset?>(null)
    }

    // State for the >=1-second Morph Animation after finger lift
    var morphStrokeNormPoints by remember(resetKey, isEraserActive) {
        mutableStateOf<List<Offset>>(emptyList())
    }
    var morphBrushRadius by remember(resetKey, isEraserActive) {
        mutableFloatStateOf(normalizedBrushRadius)
    }
    var preMorphBitmap by remember(resetKey, isEraserActive) {
        mutableStateOf<Bitmap?>(null)
    }
    val morphProgress = remember(resetKey) { Animatable(1f) }
    var activeMorphJob by remember(resetKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val currentEraserActive by rememberUpdatedState(isEraserActive)
    val currentBrushRadius by rememberUpdatedState(normalizedBrushRadius)
    val currentOnEraseFinished by rememberUpdatedState(onEraseStrokeFinished)
    val currentBitmap by rememberUpdatedState(bitmap)

    // During the first half of the morph animation, keep showing the pre-erase bitmap under the morphing highlight
    // so the user sees the marked content morph into the healed paper over the 1-second transition
    val mProg = morphProgress.value.coerceIn(0f, 1f)
    val isMorphing = morphStrokeNormPoints.isNotEmpty() && mProg < 0.999f
    val baseBitmapToDisplay = if (isMorphing && mProg < 0.55f && preMorphBitmap != null && !preMorphBitmap!!.isRecycled) {
        preMorphBitmap!!
    } else {
        bitmap
    }
    val imageBitmap = remember(baseBitmapToDisplay) { baseBitmapToDisplay.asImageBitmap() }
    val healedImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    LaunchedEffect(scale > 1.04f, isEraserActive) {
        onZoomStateChanged?.invoke((scale > 1.04f) || isEraserActive)
    }

    var previousRotation by remember(resetKey) { mutableIntStateOf(rotationDegrees) }
    val spinProgress = remember(resetKey) { Animatable(1f) }
    var spinStartScale by remember(resetKey) { mutableFloatStateOf(1f) }

    fun computeStartScaleRatio(): Float {
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        val bw = bitmap.width.toFloat().coerceAtLeast(1f)
        val bh = bitmap.height.toFloat().coerceAtLeast(1f)
        return if (cw > 10f && ch > 10f) {
            val fitAfter = min(cw / bw, ch / bh).coerceAtLeast(0.001f)
            val fitBefore = min(cw / bh, ch / bw).coerceAtLeast(0.001f)
            (fitBefore / fitAfter).coerceIn(0.45f, 2.2f)
        } else {
            1f
        }
    }

    val isStartingNewSpin = rotationDegrees != previousRotation
    val immediateStartScale = if (isStartingNewSpin) computeStartScaleRatio() else spinStartScale

    LaunchedEffect(rotationDegrees) {
        if (rotationDegrees != previousRotation) {
            scale = 1f
            offset = Offset.Zero
            activeStrokeNormPoints = emptyList()
            activeFingerScreenPos = null
            activeMorphJob?.cancel()
            morphStrokeNormPoints = emptyList()
            preMorphBitmap = null
            val startScale = computeStartScaleRatio()
            spinStartScale = startScale
            try {
                spinProgress.snapTo(0f)
                previousRotation = rotationDegrees
                spinProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 270, easing = FastOutSlowInEasing)
                )
            } finally {
                previousRotation = rotationDegrees
                spinProgress.snapTo(1f)
            }
        }
    }

    fun clampOffset(targetScale: Float, rawOffset: Offset): Offset {
        if (targetScale <= 1.01f || containerSize.width <= 0 || containerSize.height <= 0) {
            return Offset.Zero
        }
        val maxX = (containerSize.width * (targetScale - 1f)) / 2f
        val maxY = (containerSize.height * (targetScale - 1f)) / 2f
        return Offset(
            x = rawOffset.x.coerceIn(-maxX, maxX),
            y = rawOffset.y.coerceIn(-maxY, maxY)
        )
    }

    fun screenToNormalizedImage(screenPt: Offset): Offset? {
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        val bw = currentBitmap.width.toFloat().coerceAtLeast(1f)
        val bh = currentBitmap.height.toFloat().coerceAtLeast(1f)
        if (cw <= 4f || ch <= 4f) return null

        val fitScale = min(cw / bw, ch / bh).coerceAtLeast(0.0001f)
        val fittedW = bw * fitScale
        val fittedH = bh * fitScale

        val unzoomedDx = (screenPt.x - cw * 0.5f - offset.x) / scale.coerceAtLeast(0.1f)
        val unzoomedDy = (screenPt.y - ch * 0.5f - offset.y) / scale.coerceAtLeast(0.1f)

        val normX = (unzoomedDx + fittedW * 0.5f) / fittedW
        val normY = (unzoomedDy + fittedH * 0.5f) / fittedH
        if (normX !in -0.06f..1.06f || normY !in -0.06f..1.06f) return null
        return Offset(normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f))
    }

    val p = if (isStartingNewSpin) 0f else spinProgress.value.coerceIn(0f, 1f)
    val currentSpinAngle = -90f * (1f - p)
    val currentSpinScale = immediateStartScale + (1f - immediateStartScale) * p

    val pointerModifier = Modifier.pointerInput(resetKey, onZoomStateChanged != null, isEraserActive) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var multiTouchGesture = false
            val strokePoints = ArrayList<Offset>(64)

            if (currentEraserActive) {
                // If a previous morph animation was still finishing, complete it immediately on new touch
                if (morphStrokeNormPoints.isNotEmpty()) {
                    activeMorphJob?.cancel()
                    morphStrokeNormPoints = emptyList()
                    preMorphBitmap = null
                }
                screenToNormalizedImage(down.position)?.let { normPt ->
                    strokePoints.add(normPt)
                    activeStrokeNormPoints = strokePoints.toList()
                    activeFingerScreenPos = down.position
                }
            }

            do {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                val pressedCount = pressedChanges.size

                if (pressedCount >= 2) {
                    if (!multiTouchGesture) {
                        multiTouchGesture = true
                        strokePoints.clear()
                        activeStrokeNormPoints = emptyList()
                        activeFingerScreenPos = null
                    }
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale > 1.01f) {
                            clampOffset(
                                newScale,
                                Offset(offset.x + panChange.x, offset.y + panChange.y)
                            )
                        } else {
                            Offset.Zero
                        }
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                } else if (currentEraserActive && !multiTouchGesture && pressedCount == 1) {
                    val change = pressedChanges.first()
                    val pos = change.position
                    activeFingerScreenPos = pos
                    screenToNormalizedImage(pos)?.let { normPt ->
                        val last = strokePoints.lastOrNull()
                        if (last == null ||
                            kotlin.math.hypot((normPt.x - last.x).toDouble(), (normPt.y - last.y).toDouble()) >= 0.0015
                        ) {
                            strokePoints.add(normPt)
                            activeStrokeNormPoints = strokePoints.toList()
                        }
                    }
                    if (change.positionChanged()) {
                        change.consume()
                    }
                } else if (!currentEraserActive) {
                    val handleTransform = scale > 1.04f || onZoomStateChanged == null
                    if (handleTransform) {
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f || panChange != Offset.Zero) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            scale = newScale
                            offset = if (newScale > 1.01f) {
                                clampOffset(
                                    newScale,
                                    Offset(offset.x + panChange.x, offset.y + panChange.y)
                                )
                            } else {
                                Offset.Zero
                            }
                            if (newScale > 1.04f || onZoomStateChanged == null) {
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })

            if (currentEraserActive && !multiTouchGesture && strokePoints.isNotEmpty()) {
                val finishedPoints = strokePoints.toList()
                val radiusToApply = currentBrushRadius
                val capturedBeforeBmp = currentBitmap
                activeStrokeNormPoints = emptyList()
                activeFingerScreenPos = null

                // Start the >=1-second Morph Animation while background content-aware healing runs
                activeMorphJob?.cancel()
                preMorphBitmap = capturedBeforeBmp
                morphBrushRadius = radiusToApply
                morphStrokeNormPoints = finishedPoints
                currentOnEraseFinished?.invoke(finishedPoints, radiusToApply)

                activeMorphJob = scope.launch {
                    try {
                        morphProgress.snapTo(0f)
                        // Phase 1 (0..0.72 over 750ms): Accent highlighter pulses & shimmers over the marked stroke
                        morphProgress.animateTo(
                            targetValue = 0.72f,
                            animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing)
                        )
                        // If background processing on a huge image is still running, wait for it (up to 4s)
                        var waitedMs = 0L
                        while (currentBitmap === capturedBeforeBmp && waitedMs < 4000L) {
                            kotlinx.coroutines.delay(30L)
                            waitedMs += 30L
                        }
                        // Phase 2 (0.72..1.0 over 320ms): Smoothly morphs and dissolves into the healed paper shade
                        morphProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                        )
                    } finally {
                        morphStrokeNormPoints = emptyList()
                        preMorphBitmap = null
                    }
                }
            } else {
                activeStrokeNormPoints = emptyList()
                activeFingerScreenPos = null
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(resetKey, isEraserActive) {
                if (!isEraserActive) {
                    detectTapGestures(
                        onDoubleTap = { tapPos ->
                            val startScale = scale
                            val startOffset = offset
                            val targetScale = if (startScale > 1.15f) 1f else 2.8f
                            val targetOffset = if (targetScale == 1f) {
                                Offset.Zero
                            } else {
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                val focusShift = Offset(
                                    x = (centerX - tapPos.x) * (targetScale - 1f),
                                    y = (centerY - tapPos.y) * (targetScale - 1f)
                                )
                                clampOffset(targetScale, focusShift)
                            }
                            scope.launch {
                                val anim = Animatable(0f)
                                anim.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) {
                                    val t = value
                                    scale = startScale + (targetScale - startScale) * t
                                    offset = Offset(
                                        x = startOffset.x + (targetOffset.x - startOffset.x) * t,
                                        y = startOffset.y + (targetOffset.y - startOffset.y) * t
                                    )
                                }
                            }
                        }
                    )
                }
            }
            .then(pointerModifier),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale * currentSpinScale
                    scaleY = scale * currentSpinScale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = currentSpinAngle
                }
        )

        // Smooth cross-fade of the healed bitmap during the middle of the 1s morph transition (0.35 .. 0.65)
        if (isMorphing && mProg in 0.32f..0.55f && bitmap !== preMorphBitmap) {
            val crossAlpha = ((mProg - 0.32f) / 0.23f).coerceIn(0f, 1f)
            Image(
                bitmap = healedImageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale * currentSpinScale
                        scaleY = scale * currentSpinScale
                        translationX = offset.x
                        translationY = offset.y
                        rotationZ = currentSpinAngle
                        alpha = crossAlpha
                    }
            )
        }

        if (isEraserActive || isMorphing) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cw = size.width
                val ch = size.height
                val bw = bitmap.width.toFloat().coerceAtLeast(1f)
                val bh = bitmap.height.toFloat().coerceAtLeast(1f)
                val fitScale = min(cw / bw, ch / bh).coerceAtLeast(0.0001f)
                val fittedW = bw * fitScale
                val fittedH = bh * fitScale
                val minFitted = min(fittedW, fittedH)
                val screenRadius = (normalizedBrushRadius * minFitted * scale).coerceAtLeast(4f)

                fun normToScreen(pt: Offset): Offset {
                    val unzoomedDx = (pt.x - 0.5f) * fittedW
                    val unzoomedDy = (pt.y - 0.5f) * fittedH
                    return Offset(
                        x = cw * 0.5f + offset.x + unzoomedDx * scale,
                        y = ch * 0.5f + offset.y + unzoomedDy * scale
                    )
                }

                // 1. Live Accent Highlighter Paint while the finger is touching/brushing the document
                if (activeStrokeNormPoints.isNotEmpty()) {
                    if (activeStrokeNormPoints.size == 1) {
                        val sPt = normToScreen(activeStrokeNormPoints.first())
                        drawCircle(
                            color = accentColor.copy(alpha = 0.24f),
                            radius = screenRadius + 3.dp.toPx(),
                            center = sPt
                        )
                        drawCircle(
                            color = accentColor.copy(alpha = 0.52f),
                            radius = screenRadius,
                            center = sPt
                        )
                    } else {
                        val strokePath = Path()
                        val first = normToScreen(activeStrokeNormPoints[0])
                        strokePath.moveTo(first.x, first.y)
                        for (i in 1 until activeStrokeNormPoints.size) {
                            val nxt = normToScreen(activeStrokeNormPoints[i])
                            strokePath.lineTo(nxt.x, nxt.y)
                        }
                        // Outer soft highlighter glow
                        drawPath(
                            path = strokePath,
                            color = accentColor.copy(alpha = 0.22f),
                            style = Stroke(
                                width = screenRadius * 2f + 4.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        // Main vivid accent highlighter body
                        drawPath(
                            path = strokePath,
                            color = accentColor.copy(alpha = 0.52f),
                            style = Stroke(
                                width = screenRadius * 2f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                // 2. >=1-Second Morph Animation over the brushed stroke after finger lift
                if (isMorphing && morphStrokeNormPoints.isNotEmpty()) {
                    val morphScreenRadius = (morphBrushRadius * minFitted * scale).coerceAtLeast(4f)
                    val pulseWave = kotlin.math.sin(mProg * Math.PI.toFloat() * 3f)
                    val fadeOut = (1f - ((mProg - 0.58f) / 0.42f).coerceIn(0f, 1f))
                    val outerScale = 1f + 0.14f * kotlin.math.sin(mProg * Math.PI.toFloat())
                    val coreContract = (1f - mProg * 0.65f).coerceIn(0.20f, 1f)
                    val shimmerPhase = (mProg * 1.4f).coerceIn(0f, 1f)

                    if (morphStrokeNormPoints.size == 1) {
                        val sPt = normToScreen(morphStrokeNormPoints.first())
                        drawCircle(
                            color = accentColor.copy(alpha = 0.28f * fadeOut),
                            radius = morphScreenRadius * outerScale + 4.dp.toPx(),
                            center = sPt
                        )
                        drawCircle(
                            color = accentColor.copy(alpha = (0.56f + 0.14f * pulseWave).coerceIn(0f, 0.85f) * fadeOut),
                            radius = morphScreenRadius * coreContract,
                            center = sPt
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.65f * kotlin.math.sin(mProg * Math.PI.toFloat()) * fadeOut),
                            radius = morphScreenRadius * outerScale,
                            center = sPt,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    } else {
                        val morphPath = Path()
                        val first = normToScreen(morphStrokeNormPoints[0])
                        morphPath.moveTo(first.x, first.y)
                        for (i in 1 until morphStrokeNormPoints.size) {
                            val nxt = normToScreen(morphStrokeNormPoints[i])
                            morphPath.lineTo(nxt.x, nxt.y)
                        }
                        // Expanding/breathing outer accent aura
                        drawPath(
                            path = morphPath,
                            color = accentColor.copy(alpha = 0.26f * fadeOut),
                            style = Stroke(
                                width = morphScreenRadius * 2f * outerScale + 6.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        // Morphing accent highlighter body that contracts inward as the healed paper emerges
                        drawPath(
                            path = morphPath,
                            color = accentColor.copy(alpha = (0.54f + 0.14f * pulseWave).coerceIn(0f, 0.85f) * fadeOut),
                            style = Stroke(
                                width = (morphScreenRadius * 2f * coreContract).coerceAtLeast(2f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        // Luminous morphing core wave sweeping along the stroke
                        val waveAlpha = (0.58f * kotlin.math.sin(shimmerPhase * Math.PI.toFloat()) * fadeOut).coerceIn(0f, 1f)
                        if (waveAlpha > 0.02f) {
                            drawPath(
                                path = morphPath,
                                color = Color.White.copy(alpha = waveAlpha),
                                style = Stroke(
                                    width = (morphScreenRadius * (0.85f - 0.45f * mProg)).coerceAtLeast(1.5f),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                // 3. Accent-colored brush size preview while dragging the brush size slider or touching the image
                val outlineCenter = activeFingerScreenPos
                    ?: if (isAdjustingBrushSize) {
                        brushPreviewTouchOffset ?: Offset(cw * 0.5f, ch * 0.5f)
                    } else {
                        null
                    }

                if (outlineCenter != null) {
                    // Vivid translucent accent fill so the brush size stands out clearly against white paper
                    drawCircle(
                        color = accentColor.copy(alpha = if (isAdjustingBrushSize) 0.36f else 0.24f),
                        radius = screenRadius,
                        center = outlineCenter
                    )
                    // Subtle dark contrast halo outside the accent ring
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.42f),
                        radius = screenRadius + 1.8.dp.toPx(),
                        center = outlineCenter,
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                    // Crisp accent border ring
                    drawCircle(
                        color = accentColor,
                        radius = screenRadius,
                        center = outlineCenter,
                        style = Stroke(width = 2.2.dp.toPx())
                    )
                    if (isAdjustingBrushSize) {
                        drawCircle(
                            color = accentColor,
                            radius = 2.5.dp.toPx(),
                            center = outlineCenter
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ultra-thin line slider (`1.8.dp` track) with a small circular knob (`12.dp` diameter) and no label text.
 * Used for both Filter Intensity and Smart Eraser Brush Size.
 * Reports [onDragStateChanged] and [onDragTouchXChanged] so the Smart Eraser can preview the transparent
 * brush outline while the user slides the knob.
 */
@Composable
fun UltraThinLineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    activeColor: Color,
    inactiveColor: Color = Color.White.copy(alpha = 0.24f),
    onDragStateChanged: (Boolean) -> Unit = {},
    onDragFractionChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)
    val currentOnDragFractionChanged by rememberUpdatedState(onDragFractionChanged)
    var isDragging by remember { mutableStateOf(false) }

    val rangeSpan = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / rangeSpan).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    currentOnDragStateChanged(true)
                    val pad = 10.dp.toPx()
                    val usableW = (size.width - pad * 2f).coerceAtLeast(1f)

                    fun updateFromX(xPos: Float) {
                        val f = ((xPos - pad) / usableW).coerceIn(0f, 1f)
                        currentOnDragFractionChanged(f)
                        currentOnValueChange(valueRange.start + f * rangeSpan)
                    }

                    updateFromX(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val active = event.changes.firstOrNull { it.pressed }
                        if (active != null) {
                            updateFromX(active.position.x)
                            if (active.positionChanged()) active.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isDragging = false
                    currentOnDragStateChanged(false)
                    currentOnValueChangeFinished()
                }
            }
    ) {
        val pad = 10.dp.toPx()
        val usableW = (size.width - pad * 2f).coerceAtLeast(1f)
        val centerY = size.height * 0.5f
        val thumbX = pad + fraction * usableW
        val strokeW = 1.8.dp.toPx()

        // Inactive ultra-thin track
        drawLine(
            color = inactiveColor,
            start = Offset(pad, centerY),
            end = Offset(size.width - pad, centerY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )

        // Active ultra-thin track
        drawLine(
            color = activeColor,
            start = Offset(pad, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )

        // Small circular knob
        val knobRadius = if (isDragging) 6.5.dp.toPx() else 5.5.dp.toPx()
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = knobRadius + 1.5.dp.toPx(),
            center = Offset(thumbX, centerY)
        )
        drawCircle(
            color = activeColor,
            radius = knobRadius,
            center = Offset(thumbX, centerY)
        )
    }
}

/**
 * Floating borderless, textless icon button with a subtle dark translucent backdrop pill
 * (`Color.Black.copy(alpha = 0.35f)`), used for the bottom-right Rotate button and the
 * bottom-left vertically arranged Undo/Redo/Reset buttons.
 */
@Composable
fun FloatingOverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(4.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.32f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Vertically arranged Undo, Redo, and Reset floating icon pill for the bottom-left of the image viewer
 * when Smart Eraser is active. No border, no text, subtle `Color.Black.copy(alpha = 0.35f)` backdrop.
 */
@Composable
fun VerticalEraserHistoryColumn(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    undoIcon: ImageVector = Icons.AutoMirrored.Filled.Undo,
    redoIcon: ImageVector = Icons.AutoMirrored.Filled.Redo,
    resetIcon: ImageVector = Icons.Default.RestartAlt,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(vertical = 2.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = canUndo, onClick = onUndo)
                .testTag("eraser_undo_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = undoIcon,
                contentDescription = "Undo",
                tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.32f),
                modifier = Modifier.size(20.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = canRedo, onClick = onRedo)
                .testTag("eraser_redo_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = redoIcon,
                contentDescription = "Redo",
                tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.32f),
                modifier = Modifier.size(20.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = canReset, onClick = onReset)
                .testTag("eraser_reset_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = resetIcon,
                contentDescription = "Reset",
                tint = if (canReset) Color.White else Color.White.copy(alpha = 0.32f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Maps each [ScanFilter] to a clean, recognizable vector icon for the compact thumbnail-free filter strip.
 */
fun filterIconFor(filter: ScanFilter): ImageVector {
    return when (filter) {
        ScanFilter.ORIGINAL -> Icons.Default.Image
        ScanFilter.MAGIC_COLOR -> Icons.Default.AutoFixHigh
        ScanFilter.AI_ENHANCE -> Icons.Default.AutoAwesome
        ScanFilter.WHITEBOARD -> Icons.Default.BrightnessHigh
        ScanFilter.CLEAR_BW -> Icons.Default.Contrast
        ScanFilter.GRAYSCALE -> Icons.Default.FilterBAndW
    }
}

fun compactFilterLabel(filter: ScanFilter, isBn: Boolean): String {
    return when (filter) {
        ScanFilter.ORIGINAL -> if (isBn) "আসল" else "Original"
        ScanFilter.MAGIC_COLOR -> if (isBn) "ম্যাজিক কালার" else "Magic Color"
        ScanFilter.AI_ENHANCE -> if (isBn) "AI এনহ্যান্স" else "AI Enhance"
        ScanFilter.WHITEBOARD -> if (isBn) "হোয়াইটবোর্ড" else "Whiteboard"
        ScanFilter.CLEAR_BW -> if (isBn) "সাদা-কালো" else "B&W"
        ScanFilter.GRAYSCALE -> if (isBn) "গ্রেস্কেল" else "Grayscale"
    }
}
