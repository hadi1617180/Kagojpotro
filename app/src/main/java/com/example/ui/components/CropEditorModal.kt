package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.AppStrings
import com.example.data.NormalizedQuad
import com.example.processing.DocumentImageProcessor
import com.example.ui.theme.LocalGlassPalette
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.ObsidianSurface
import com.example.util.HapticsAndShareHelper
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive 8-handle (4 corners + 4 mid-edge bars) perspective "Crop" editor over the full
 * immutable original image:
 * - Page title is strictly "Crop"
 * - Large circular magnifier placed opposite the held handle with border-accurate crosshair
 * - Bottom right: "Apply crop" button with a transparent-background Auto-Edges FAB directly over it
 */
@Composable
fun CropOriginalOverlay(
    originalBitmap: Bitmap,
    initialQuad: NormalizedQuad,
    strings: AppStrings,
    onCancel: () -> Unit,
    onApplyCrop: (NormalizedQuad) -> Unit
) {
    val view = LocalView.current
    val glass = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var workingQuad by remember(initialQuad) { mutableStateOf(initialQuad.clamped()) }
    var isAutoDetecting by remember { mutableStateOf(false) }
    val currentQuadState by rememberUpdatedState(workingQuad)

    // -1 = none, 0..3 = TL/TR/BR/BL corners, 4..7 = Top/Right/Bottom/Left mid-edge bars
    var activeHandleIndex by remember { mutableIntStateOf(-1) }
    val imageBitmap = remember(originalBitmap) { originalBitmap.asImageBitmap() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("crop_original_overlay"),
        color = ObsidianDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // Top Header: Close button on left, centered "Crop" title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = {
                        HapticsAndShareHelper.tapTick(view)
                        onCancel()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("crop_cancel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.cancel,
                        tint = Color.White
                    )
                }

                Text(
                    text = strings.cropOriginal,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("crop_page_title")
                )

                Spacer(modifier = Modifier.width(48.dp))
            }

            // Main Interactive Crop Area + Bottom-Right Floating Auto-Edges FAB & Apply Crop Button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 136.dp)
                        .testTag("crop_canvas")
                        .pointerInput(originalBitmap) {
                            var imgRect = Rect.Zero
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val cw = size.width.toFloat()
                                    val ch = size.height.toFloat()
                                    val bw = originalBitmap.width.toFloat().coerceAtLeast(1f)
                                    val bh = originalBitmap.height.toFloat().coerceAtLeast(1f)
                                    val scale = min(cw / bw, ch / bh)
                                    val dw = bw * scale
                                    val dh = bh * scale
                                    val left = (cw - dw) / 2f
                                    val top = (ch - dh) / 2f
                                    imgRect = Rect(left, top, left + dw, top + dh)

                                    val q = currentQuadState
                                    val tl = Offset(left + q.tlX * dw, top + q.tlY * dh)
                                    val tr = Offset(left + q.trX * dw, top + q.trY * dh)
                                    val br = Offset(left + q.brX * dw, top + q.brY * dh)
                                    val bl = Offset(left + q.blX * dw, top + q.blY * dh)

                                    val handles = listOf(
                                        tl,
                                        tr,
                                        br,
                                        bl,
                                        Offset((tl.x + tr.x) * 0.5f, (tl.y + tr.y) * 0.5f),
                                        Offset((tr.x + br.x) * 0.5f, (tr.y + br.y) * 0.5f),
                                        Offset((br.x + bl.x) * 0.5f, (br.y + bl.y) * 0.5f),
                                        Offset((bl.x + tl.x) * 0.5f, (bl.y + tl.y) * 0.5f)
                                    )

                                    var bestIdx = -1
                                    var bestDist = Float.MAX_VALUE
                                    for (i in handles.indices) {
                                        val d = hypot(
                                            (startOffset.x - handles[i].x).toDouble(),
                                            (startOffset.y - handles[i].y).toDouble()
                                        ).toFloat()
                                        if (d < bestDist) {
                                            bestDist = d
                                            bestIdx = i
                                        }
                                    }
                                    if (bestIdx in 0..7 && bestDist <= 56.dp.toPx()) {
                                        activeHandleIndex = bestIdx
                                        HapticsAndShareHelper.tapTick(view)
                                    } else {
                                        activeHandleIndex = -1
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val handleIdx = activeHandleIndex
                                    if (handleIdx in 0..7 && imgRect.width > 10f && imgRect.height > 10f) {
                                        val dx = dragAmount.x / imgRect.width
                                        val dy = dragAmount.y / imgRect.height
                                        val q = currentQuadState
                                        workingQuad = if (handleIdx in 0..3) {
                                            moveCornerConvexSafe(q, handleIdx, dx, dy)
                                        } else {
                                            moveEdgeConvexSafe(q, handleIdx - 4, dx, dy)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    activeHandleIndex = -1
                                    HapticsAndShareHelper.tapTick(view)
                                },
                                onDragCancel = {
                                    activeHandleIndex = -1
                                }
                            )
                        }
                ) {
                    val cw = size.width
                    val ch = size.height
                    val bw = originalBitmap.width.toFloat().coerceAtLeast(1f)
                    val bh = originalBitmap.height.toFloat().coerceAtLeast(1f)
                    val scale = min(cw / bw, ch / bh)
                    val dw = bw * scale
                    val dh = bh * scale
                    val left = (cw - dw) / 2f
                    val top = (ch - dh) / 2f

                    // 1. Draw the full uncropped original image
                    drawImage(
                        image = imageBitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(originalBitmap.width, originalBitmap.height),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt())
                    )

                    val tl = Offset(left + workingQuad.tlX * dw, top + workingQuad.tlY * dh)
                    val tr = Offset(left + workingQuad.trX * dw, top + workingQuad.trY * dh)
                    val br = Offset(left + workingQuad.brX * dw, top + workingQuad.brY * dh)
                    val bl = Offset(left + workingQuad.blX * dw, top + workingQuad.blY * dh)

                    // 2. Darken exterior outside the quadrilateral
                    val maskPath = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(left, top, left + dw, top + dh))
                        moveTo(tl.x, tl.y)
                        lineTo(tr.x, tr.y)
                        lineTo(br.x, br.y)
                        lineTo(bl.x, bl.y)
                        close()
                    }
                    drawPath(
                        path = maskPath,
                        color = Color.Black.copy(alpha = 0.54f)
                    )

                    // 3. Draw quad outline
                    val quadPath = Path().apply {
                        moveTo(tl.x, tl.y)
                        lineTo(tr.x, tr.y)
                        lineTo(br.x, br.y)
                        lineTo(bl.x, bl.y)
                        close()
                    }
                    drawPath(
                        path = quadPath,
                        color = glass.accentHighlight,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    val corners = listOf(tl, tr, br, bl)

                    // 4. Draw the 4 mid-edge pill handles
                    for (e in 0 until 4) {
                        val pA = corners[e]
                        val pB = corners[(e + 1) % 4]
                        val mid = Offset((pA.x + pB.x) * 0.5f, (pA.y + pB.y) * 0.5f)
                        val angleDeg = Math.toDegrees(
                            atan2((pB.y - pA.y).toDouble(), (pB.x - pA.x).toDouble())
                        ).toFloat()
                        val isActive = (e + 4) == activeHandleIndex
                        val barWidth = if (isActive) 34.dp.toPx() else 28.dp.toPx()
                        val barHeight = if (isActive) 9.dp.toPx() else 7.5.dp.toPx()
                        val innerW = barWidth - 4.dp.toPx()
                        val innerH = barHeight - 3.dp.toPx()

                        rotate(degrees = angleDeg, pivot = mid) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(mid.x - barWidth * 0.5f, mid.y - barHeight * 0.5f),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barHeight * 0.5f, barHeight * 0.5f)
                            )
                            drawRoundRect(
                                color = if (isActive) glass.accentHighlight else glass.accent,
                                topLeft = Offset(mid.x - innerW * 0.5f, mid.y - innerH * 0.5f),
                                size = Size(innerW, innerH),
                                cornerRadius = CornerRadius(innerH * 0.5f, innerH * 0.5f)
                            )
                        }
                    }

                    // 5. Draw the 4 corner handles
                    corners.forEachIndexed { idx, pt ->
                        val isActive = idx == activeHandleIndex
                        val outerRadius = if (isActive) 16.dp.toPx() else 14.dp.toPx()
                        drawCircle(
                            color = Color.White,
                            radius = outerRadius,
                            center = pt
                        )
                        drawCircle(
                            color = if (isActive) glass.accentHighlight else glass.accent,
                            radius = outerRadius - 3.dp.toPx(),
                            center = pt
                        )
                    }

                    // 6. Large circular magnifier placed opposite the held corner or mid-edge handle,
                    //    with border-safe sampling so crosshair stays dead-center on the exact handle point
                    if (activeHandleIndex in 0..7) {
                        val (activeNormX, activeNormY) = when (activeHandleIndex) {
                            0 -> workingQuad.tlX to workingQuad.tlY
                            1 -> workingQuad.trX to workingQuad.trY
                            2 -> workingQuad.brX to workingQuad.brY
                            3 -> workingQuad.blX to workingQuad.blY
                            4 -> ((workingQuad.tlX + workingQuad.trX) * 0.5f) to ((workingQuad.tlY + workingQuad.trY) * 0.5f)
                            5 -> ((workingQuad.trX + workingQuad.brX) * 0.5f) to ((workingQuad.trY + workingQuad.brY) * 0.5f)
                            6 -> ((workingQuad.brX + workingQuad.blX) * 0.5f) to ((workingQuad.brY + workingQuad.blY) * 0.5f)
                            else -> ((workingQuad.blX + workingQuad.tlX) * 0.5f) to ((workingQuad.blY + workingQuad.tlY) * 0.5f)
                        }

                        val loupeRadius = min(78.dp.toPx(), min(cw, ch) * 0.28f)
                        val pad = 8.dp.toPx()

                        val loupeCenter = when (activeHandleIndex) {
                            0 -> Offset(cw - loupeRadius - pad, ch - loupeRadius - pad)
                            1 -> Offset(loupeRadius + pad, ch - loupeRadius - pad)
                            2 -> Offset(loupeRadius + pad, loupeRadius + pad)
                            3 -> Offset(cw - loupeRadius - pad, loupeRadius + pad)
                            4 -> Offset(cw * 0.5f, ch - loupeRadius - pad)
                            5 -> Offset(loupeRadius + pad, ch * 0.5f)
                            6 -> Offset(cw * 0.5f, loupeRadius + pad)
                            else -> Offset(cw - loupeRadius - pad, ch * 0.5f)
                        }

                        val loupePath = Path().apply {
                            addOval(
                                Rect(
                                    loupeCenter.x - loupeRadius,
                                    loupeCenter.y - loupeRadius,
                                    loupeCenter.x + loupeRadius,
                                    loupeCenter.y + loupeRadius
                                )
                            )
                        }

                        val imgW = originalBitmap.width.toFloat().coerceAtLeast(1f)
                        val imgH = originalBitmap.height.toFloat().coerceAtLeast(1f)
                        val idealCenterX = activeNormX * imgW
                        val idealCenterY = activeNormY * imgH
                        val sampleHalf = (min(imgW, imgH) * 0.055f).coerceAtLeast(14f)
                        val sampleSpan = sampleHalf * 2f

                        val idealLeft = idealCenterX - sampleHalf
                        val idealTop = idealCenterY - sampleHalf
                        val idealRight = idealCenterX + sampleHalf
                        val idealBottom = idealCenterY + sampleHalf

                        val validSrcLeft = idealLeft.coerceIn(0f, imgW)
                        val validSrcTop = idealTop.coerceIn(0f, imgH)
                        val validSrcRight = idealRight.coerceIn(0f, imgW)
                        val validSrcBottom = idealBottom.coerceIn(0f, imgH)

                        val loupeDiameter = loupeRadius * 2f
                        val dstBaseLeft = loupeCenter.x - loupeRadius
                        val dstBaseTop = loupeCenter.y - loupeRadius

                        clipPath(loupePath) {
                            drawCircle(
                                color = ObsidianDark,
                                radius = loupeRadius,
                                center = loupeCenter
                            )
                            if (validSrcRight - validSrcLeft >= 1f && validSrcBottom - validSrcTop >= 1f) {
                                val dstLeft = dstBaseLeft + ((validSrcLeft - idealLeft) / sampleSpan) * loupeDiameter
                                val dstTop = dstBaseTop + ((validSrcTop - idealTop) / sampleSpan) * loupeDiameter
                                val dstW = ((validSrcRight - validSrcLeft) / sampleSpan) * loupeDiameter
                                val dstH = ((validSrcBottom - validSrcTop) / sampleSpan) * loupeDiameter

                                val srcOffX = validSrcLeft.roundToInt().coerceIn(0, originalBitmap.width - 1)
                                val srcOffY = validSrcTop.roundToInt().coerceIn(0, originalBitmap.height - 1)
                                val srcW = (validSrcRight - validSrcLeft).roundToInt()
                                    .coerceIn(1, originalBitmap.width - srcOffX)
                                val srcH = (validSrcBottom - validSrcTop).roundToInt()
                                    .coerceIn(1, originalBitmap.height - srcOffY)

                                drawImage(
                                    image = imageBitmap,
                                    srcOffset = IntOffset(srcOffX, srcOffY),
                                    srcSize = IntSize(srcW, srcH),
                                    dstOffset = IntOffset(dstLeft.roundToInt(), dstTop.roundToInt()),
                                    dstSize = IntSize(dstW.roundToInt().coerceAtLeast(1), dstH.roundToInt().coerceAtLeast(1))
                                )
                            }

                            // Precision crosshair at the center of the magnifier
                            val chLen = loupeRadius * 0.48f
                            drawLine(
                                color = glass.accentHighlight,
                                start = Offset(loupeCenter.x - chLen, loupeCenter.y),
                                end = Offset(loupeCenter.x + chLen, loupeCenter.y),
                                strokeWidth = 2.5.dp.toPx()
                            )
                            drawLine(
                                color = glass.accentHighlight,
                                start = Offset(loupeCenter.x, loupeCenter.y - chLen),
                                end = Offset(loupeCenter.x, loupeCenter.y + chLen),
                                strokeWidth = 2.5.dp.toPx()
                            )
                            drawCircle(
                                color = glass.accent,
                                radius = 4.dp.toPx(),
                                center = loupeCenter,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }

                        drawCircle(
                            color = Color.White,
                            radius = loupeRadius,
                            center = loupeCenter,
                            style = Stroke(width = 3.5.dp.toPx())
                        )
                        drawCircle(
                            color = glass.accent,
                            radius = loupeRadius + 2.5.dp.toPx(),
                            center = loupeCenter,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // Bottom-Right Controls: Transparent-background Auto-Edges FAB directly over Apply Crop button
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .alpha(if (isAutoDetecting) 0.55f else 1f)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(
                                width = 1.8.dp,
                                color = glass.accentHighlight.copy(alpha = 0.85f),
                                shape = CircleShape
                            )
                            .clickable(enabled = !isAutoDetecting) {
                                HapticsAndShareHelper.tapTick(view)
                                isAutoDetecting = true
                                scope.launch {
                                    val detected = withContext(Dispatchers.Default) {
                                        DocumentImageProcessor.detectDocumentCorners(originalBitmap)
                                    }
                                    workingQuad = detected.clamped()
                                    isAutoDetecting = false
                                }
                            }
                            .testTag("crop_auto_edges_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = strings.autoDetectEdges,
                            tint = glass.accentHighlight,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Button(
                        onClick = {
                            HapticsAndShareHelper.tapTick(view)
                            onApplyCrop(workingQuad.clamped())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.accent,
                            contentColor = glass.onAccent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("crop_apply_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.applyCrop,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun moveCornerConvexSafe(
    quad: NormalizedQuad,
    cornerIdx: Int,
    dx: Float,
    dy: Float
): NormalizedQuad {
    fun applyDelta(xStep: Float, yStep: Float): NormalizedQuad {
        return when (cornerIdx) {
            0 -> quad.copy(
                tlX = (quad.tlX + xStep).coerceIn(0f, 1f),
                tlY = (quad.tlY + yStep).coerceIn(0f, 1f)
            )
            1 -> quad.copy(
                trX = (quad.trX + xStep).coerceIn(0f, 1f),
                trY = (quad.trY + yStep).coerceIn(0f, 1f)
            )
            2 -> quad.copy(
                brX = (quad.brX + xStep).coerceIn(0f, 1f),
                brY = (quad.brY + yStep).coerceIn(0f, 1f)
            )
            else -> quad.copy(
                blX = (quad.blX + xStep).coerceIn(0f, 1f),
                blY = (quad.blY + yStep).coerceIn(0f, 1f)
            )
        }
    }

    val both = applyDelta(dx, dy)
    if (DocumentImageProcessor.isValidConvexQuad(both)) return both
    val xOnly = applyDelta(dx, 0f)
    if (DocumentImageProcessor.isValidConvexQuad(xOnly)) return xOnly
    val yOnly = applyDelta(0f, dy)
    if (DocumentImageProcessor.isValidConvexQuad(yOnly)) return yOnly
    return quad
}

private fun moveEdgeConvexSafe(
    quad: NormalizedQuad,
    edgeIdx: Int,
    dx: Float,
    dy: Float
): NormalizedQuad {
    fun applyEdgeDelta(rawDx: Float, rawDy: Float): NormalizedQuad {
        val (ax, ay, bx, by) = when (edgeIdx) {
            0 -> floatArrayOf(quad.tlX, quad.tlY, quad.trX, quad.trY)
            1 -> floatArrayOf(quad.trX, quad.trY, quad.brX, quad.brY)
            2 -> floatArrayOf(quad.brX, quad.brY, quad.blX, quad.blY)
            else -> floatArrayOf(quad.blX, quad.blY, quad.tlX, quad.tlY)
        }
        val minAllowedDx = maxOf(-ax, -bx)
        val maxAllowedDx = minOf(1f - ax, 1f - bx)
        val minAllowedDy = maxOf(-ay, -by)
        val maxAllowedDy = minOf(1f - ay, 1f - by)
        val clampedDx = rawDx.coerceIn(minAllowedDx, maxAllowedDx)
        val clampedDy = rawDy.coerceIn(minAllowedDy, maxAllowedDy)

        return when (edgeIdx) {
            0 -> quad.copy(
                tlX = ax + clampedDx,
                tlY = ay + clampedDy,
                trX = bx + clampedDx,
                trY = by + clampedDy
            )
            1 -> quad.copy(
                trX = ax + clampedDx,
                trY = ay + clampedDy,
                brX = bx + clampedDx,
                brY = by + clampedDy
            )
            2 -> quad.copy(
                brX = ax + clampedDx,
                brY = ay + clampedDy,
                blX = bx + clampedDx,
                blY = by + clampedDy
            )
            else -> quad.copy(
                blX = ax + clampedDx,
                blY = ay + clampedDy,
                tlX = bx + clampedDx,
                tlY = by + clampedDy
            )
        }
    }

    val both = applyEdgeDelta(dx, dy)
    if (DocumentImageProcessor.isValidConvexQuad(both)) return both
    val xOnly = applyEdgeDelta(dx, 0f)
    if (DocumentImageProcessor.isValidConvexQuad(xOnly)) return xOnly
    val yOnly = applyEdgeDelta(0f, dy)
    if (DocumentImageProcessor.isValidConvexQuad(yOnly)) return yOnly
    return quad
}
