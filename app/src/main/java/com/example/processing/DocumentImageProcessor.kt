package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import com.example.data.NormalizedQuad
import com.example.data.ScanFilter
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-precision multi-cue document boundary detector (Color-Sobel + NMS + Gradient-Guided
 * Polarity Hough + Illumination/Chromaticity Segmentation + Douglas-Peucker Quad Reduction +
 * Edge-Alignment & Inside/Outside Contrast Scorer + One-Euro adaptive velocity filter),
 * Skia-accelerated 3D perspective rectification, and 5 shadow-free Integral-Image filters
 * (Original, Magic Color, Whiteboard, Clear B&W, Grayscale).
 */
object DocumentImageProcessor {

    fun init(context: Context) {
        AiDocumentEnhancer.init(context)
    }

    fun isAiEnhanceReady(): Boolean = AiDocumentEnhancer.isModelAssetPresent()

    /**
     * Memory-safe, EXIF-orientation-aware sampled bitmap decoder shared across SingleImageScreen,
     * BatchReviewScreen, and CameraCaptureScreen. Uses orthogonal pixel rotation (`rotateBitmap`)
     * so both device runtime and Robolectric tests preserve orientation cleanly.
     */
    fun decodeSampledBitmapWithExif(path: String, maxDim: Int): Bitmap? {
        if (path.isBlank()) return null
        return runCatching {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return null
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOpts)
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            var inSample = 1
            if (srcW > 0 && srcH > 0) {
                while (maxOf(srcW / inSample, srcH / inSample) > maxDim * 2) {
                    inSample *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeFile(path, decodeOpts) ?: return null
            val exifOrientation = runCatching {
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val exifDegrees = when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
            val oriented = if (exifDegrees != 0) {
                val rot = rotateBitmap(sampled, exifDegrees)
                if (rot !== sampled && !sampled.isRecycled) sampled.recycle()
                rot
            } else {
                sampled
            }
            val curMax = maxOf(oriented.width, oriented.height)
            if (curMax <= maxDim) return@runCatching oriented
            val scale = maxDim.toFloat() / curMax.toFloat()
            val targetW = (oriented.width * scale).toInt().coerceAtLeast(1)
            val targetH = (oriented.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(oriented, targetW, targetH, true).also {
                if (it !== oriented && !oriented.isRecycled) oriented.recycle()
            }
        }.getOrNull()
    }

    private data class HullPoint(val x: Int, val y: Int) : Comparable<HullPoint> {
        override fun compareTo(other: HullPoint): Int {
            return if (this.x != other.x) this.x.compareTo(other.x) else this.y.compareTo(other.y)
        }
    }

    private data class PointF2D(val x: Float, val y: Float)

    private data class HoughLine(
        val cosT: Float,
        val sinT: Float,
        val rho: Float,
        val angleDeg: Float,
        val score: Float
    )

    private data class Line2D(val a: Float, val b: Float, val c: Float)

    /**
     * Detects the 4 corners of the primary document sheet in [bitmap] using a multi-hypothesis
     * computer vision pipeline modeled on modern mobile scanner post-refinement:
     * 1. Downsamples to 320px and applies a 3x3 Gaussian denoise filter on R, G, B, and Luminance.
     * 2. Computes multi-channel Color Sobel gradients (capturing both luminance and chroma edges)
     *    plus directional Non-Maximum Suppression (NMS) for crisp 1px edge peaks.
     * 3. Generates candidate convex quadrilaterals from complementary extractors:
     *    - Gradient-Guided Polarity-Aware Hough line pairs (handles tilted pages, shadows, and
     *      light-on-light paper/desk boundaries without needing a thresholded blob).
     *    - Illumination-normalized + chromaticity-distance paper mask (resilient to harsh shadows)
     *      plus classic adaptive paperness and border-contrast masks, closed with 5x5 morphology,
     *      reduced via minimum-area-loss polygon simplification, and refined by normal-ray
     *      total-least-squares edge fitting.
     * 4. Scores every valid convex candidate by perimeter directional-edge alignment AND
     *    inside-vs-outside RGB contrast (rejecting inner tables/text boxes in favor of the
     *    true outer document perimeter).
     */
    fun detectDocumentCorners(bitmap: Bitmap): NormalizedQuad {
        val sampleMax = 320
        val scale = min(1f, sampleMax.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1))
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(48)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(48)
        val small = if (w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        val total = w * h
        val pixels = IntArray(total)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap && !small.isRecycled) {
            small.recycle()
        }

        val rawR = IntArray(total)
        val rawG = IntArray(total)
        val rawB = IntArray(total)
        val rawL = IntArray(total)
        for (i in 0 until total) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            rawR[i] = r
            rawG[i] = g
            rawB[i] = b
            rawL[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // 3x3 Gaussian-like denoise pass to suppress paper grain and fine desk texture
        val sR = IntArray(total)
        val sG = IntArray(total)
        val sB = IntArray(total)
        val sL = IntArray(total)
        for (y in 0 until h) {
            val rowOff = y * w
            val yUp = if (y > 0) -w else 0
            val yDn = if (y < h - 1) w else 0
            for (x in 0 until w) {
                val idx = rowOff + x
                val xL = if (x > 0) -1 else 0
                val xR = if (x < w - 1) 1 else 0
                val tl = idx + yUp + xL
                val tc = idx + yUp
                val tr = idx + yUp + xR
                val ml = idx + xL
                val mr = idx + xR
                val bl = idx + yDn + xL
                val bc = idx + yDn
                val br = idx + yDn + xR
                sR[idx] = (rawR[tl] + 2 * rawR[tc] + rawR[tr] + 2 * rawR[ml] + 4 * rawR[idx] + 2 * rawR[mr] + rawR[bl] + 2 * rawR[bc] + rawR[br]) shr 4
                sG[idx] = (rawG[tl] + 2 * rawG[tc] + rawG[tr] + 2 * rawG[ml] + 4 * rawG[idx] + 2 * rawG[mr] + rawG[bl] + 2 * rawG[bc] + rawG[br]) shr 4
                sB[idx] = (rawB[tl] + 2 * rawB[tc] + rawB[tr] + 2 * rawB[ml] + 4 * rawB[idx] + 2 * rawB[mr] + rawB[bl] + 2 * rawB[bc] + rawB[br]) shr 4
                sL[idx] = (rawL[tl] + 2 * rawL[tc] + rawL[tr] + 2 * rawL[ml] + 4 * rawL[idx] + 2 * rawL[mr] + rawL[bl] + 2 * rawL[bc] + rawL[br]) shr 4
            }
        }

        // Integral image on smoothed luminance for O(1) local-background contrast checks
        val iw = w + 1
        val integralL = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            val srcRow = y * w
            val dstRow = (y + 1) * iw
            val prevRow = y * iw
            for (x in 0 until w) {
                rowSum += sL[srcRow + x]
                integralL[dstRow + x + 1] = integralL[prevRow + x + 1] + rowSum
            }
        }

        fun localMeanL(cx: Int, cy: Int, radius: Int): Float {
            val x0 = (cx - radius).coerceIn(0, w - 1)
            val y0 = (cy - radius).coerceIn(0, h - 1)
            val x1 = (cx + radius).coerceIn(0, w - 1)
            val y1 = (cy + radius).coerceIn(0, h - 1)
            val area = ((x1 - x0 + 1) * (y1 - y0 + 1)).coerceAtLeast(1)
            val sum = integralL[(y1 + 1) * iw + (x1 + 1)] -
                integralL[y0 * iw + (x1 + 1)] -
                integralL[(y1 + 1) * iw + x0] +
                integralL[y0 * iw + x0]
            return sum.toFloat() / area
        }

        // Multi-channel Color Sobel gradients (luminance + R/G/B max gradient vector)
        val gxArr = IntArray(total)
        val gyArr = IntArray(total)
        val gradMag = IntArray(total)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val gxL = (-sL[idx - w - 1] + sL[idx - w + 1] - 2 * sL[idx - 1] + 2 * sL[idx + 1] - sL[idx + w - 1] + sL[idx + w + 1]) shr 2
                val gyL = (-sL[idx - w - 1] - 2 * sL[idx - w] - sL[idx - w + 1] + sL[idx + w - 1] + 2 * sL[idx + w] + sL[idx + w + 1]) shr 2

                val gxR = (-sR[idx - w - 1] + sR[idx - w + 1] - 2 * sR[idx - 1] + 2 * sR[idx + 1] - sR[idx + w - 1] + sR[idx + w + 1]) shr 2
                val gyR = (-sR[idx - w - 1] - 2 * sR[idx - w] - sR[idx - w + 1] + sR[idx + w - 1] + 2 * sR[idx + w] + sR[idx + w + 1]) shr 2

                val gxG = (-sG[idx - w - 1] + sG[idx - w + 1] - 2 * sG[idx - 1] + 2 * sG[idx + 1] - sG[idx + w - 1] + sG[idx + w + 1]) shr 2
                val gyG = (-sG[idx - w - 1] - 2 * sG[idx - w] - sG[idx - w + 1] + sG[idx + w - 1] + 2 * sG[idx + w] + sG[idx + w + 1]) shr 2

                val gxB = (-sB[idx - w - 1] + sB[idx - w + 1] - 2 * sB[idx - 1] + 2 * sB[idx + 1] - sB[idx + w - 1] + sB[idx + w + 1]) shr 2
                val gyB = (-sB[idx - w - 1] - 2 * sB[idx - w] - sB[idx - w + 1] + sB[idx + w - 1] + 2 * sB[idx + w] + sB[idx + w + 1]) shr 2

                val mL = gxL * gxL + gyL * gyL
                val mR = gxR * gxR + gyR * gyR
                val mG = gxG * gxG + gyG * gyG
                val mB = gxB * gxB + gyB * gyB

                var bestGx = gxL
                var bestGy = gyL
                var bestM = mL
                if (mR > bestM) { bestM = mR; bestGx = gxR; bestGy = gyR }
                if (mG > bestM) { bestM = mG; bestGx = gxG; bestGy = gyG }
                if (mB > bestM) { bestM = mB; bestGx = gxB; bestGy = gyB }

                gxArr[idx] = bestGx
                gyArr[idx] = bestGy
                gradMag[idx] = sqrt(bestM.toFloat()).roundToInt()
            }
        }

        // Directional Non-Maximum Suppression (NMS) skipping 2px outer frame margin
        val nmsMag = IntArray(total)
        val nmsHist = IntArray(512)
        var nmsNonZeroCount = 0
        for (y in 2 until h - 2) {
            val row = y * w
            for (x in 2 until w - 2) {
                val idx = row + x
                val g = gradMag[idx]
                if (g < 8) continue
                val ax = abs(gxArr[idx])
                val ay = abs(gyArr[idx])
                val isPeak = when {
                    ay * 12 <= ax * 5 -> g >= gradMag[idx - 1] && g >= gradMag[idx + 1]
                    ax * 12 <= ay * 5 -> g >= gradMag[idx - w] && g >= gradMag[idx + w]
                    (gxArr[idx] xor gyArr[idx]) >= 0 -> g >= gradMag[idx - w - 1] && g >= gradMag[idx + w + 1]
                    else -> g >= gradMag[idx - w + 1] && g >= gradMag[idx + w - 1]
                }
                if (isPeak) {
                    nmsMag[idx] = g
                    nmsHist[g.coerceIn(0, 511)]++
                    nmsNonZeroCount++
                }
            }
        }

        var strongEdgeThresh = 22
        if (nmsNonZeroCount > 30) {
            val targetRank = (nmsNonZeroCount * 0.72f).toInt()
            var acc = 0
            for (v in 0 until 512) {
                acc += nmsHist[v]
                if (acc >= targetRank) {
                    strongEdgeThresh = v.coerceIn(12, 68)
                    break
                }
            }
        }

        val candidates = ArrayList<Array<PointF2D>>(32)

        // 1) Hypothesis Family A: Gradient-Guided Polarity-Aware Hough Line Intersections
        extractHoughCandidates(
            w = w,
            h = h,
            nmsMag = nmsMag,
            gxArr = gxArr,
            gyArr = gyArr,
            strongEdgeThresh = strongEdgeThresh,
            outCandidates = candidates
        )

        // 2) Compute Paper & Desk Color Statistics for Segmentation Hypotheses
        val cx0 = (w * 0.24f).toInt()
        val cx1 = (w * 0.76f).toInt()
        val cy0 = (h * 0.22f).toInt()
        val cy1 = (h * 0.78f).toInt()
        val centerLums = IntArray(((cx1 - cx0 + 1) * (cy1 - cy0 + 1)).coerceAtLeast(1))
        var clCount = 0
        for (y in cy0..cy1) {
            val row = y * w
            for (x in cx0..cx1) {
                centerLums[clCount++] = sL[row + x]
            }
        }
        centerLums.sort(0, clCount)
        val p45Lum = centerLums[(clCount * 45) / 100]

        var pRSum = 0L; var pGSum = 0L; var pBSum = 0L; var pLSum = 0L; var pCount = 0
        for (y in cy0..cy1) {
            val row = y * w
            for (x in cx0..cx1) {
                val idx = row + x
                if (sL[idx] >= p45Lum && gradMag[idx] < 28) {
                    pRSum += sR[idx]
                    pGSum += sG[idx]
                    pBSum += sB[idx]
                    pLSum += sL[idx]
                    pCount++
                }
            }
        }
        val paperR = if (pCount > 0) (pRSum / pCount).toInt() else 220
        val paperG = if (pCount > 0) (pGSum / pCount).toInt() else 220
        val paperB = if (pCount > 0) (pBSum / pCount).toInt() else 215
        val paperL = if (pCount > 0) (pLSum / pCount).toInt() else 220
        val paperSum = (paperR + paperG + paperB).coerceAtLeast(30).toFloat()
        val cnR = paperR / paperSum
        val cnG = paperG / paperSum
        val cnB = paperB / paperSum

        // Outer border reference color
        var bgRSum = 0L; var bgGSum = 0L; var bgBSum = 0L; var bgCount = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (x < 4 || x >= w - 4 || y < 4 || y >= h - 4) {
                    val idx = row + x
                    bgRSum += sR[idx]
                    bgGSum += sG[idx]
                    bgBSum += sB[idx]
                    bgCount++
                }
            }
        }
        val bgR = if (bgCount > 0) (bgRSum / bgCount).toInt() else 50
        val bgG = if (bgCount > 0) (bgGSum / bgCount).toInt() else 50
        val bgB = if (bgCount > 0) (bgBSum / bgCount).toInt() else 50
        val paperBgDiff = abs(paperR - bgR) + abs(paperG - bgG) + abs(paperB - bgB)

        // Hypothesis Family B1: Shadow-Resilient Chromaticity + Local Illumination Mask
        val localRadius = max(14, min(w, h) / 5)
        val chromaMask = BooleanArray(total)
        val minPaperLum = (paperL * 0.36f).toInt().coerceAtLeast(48)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val r = sR[idx]
                val g = sG[idx]
                val b = sB[idx]
                val sum = (r + g + b).coerceAtLeast(24).toFloat()
                val cDist = abs(r / sum - cnR) + abs(g / sum - cnG) + abs(b / sum - cnB)
                val locMean = localMeanL(x, y, localRadius)
                if (cDist <= 0.12f && sL[idx] >= minPaperLum && sL[idx] >= locMean * 0.68f) {
                    chromaMask[idx] = true
                }
            }
        }
        extractMaskCandidates(w, h, chromaMask, gxArr, gyArr, gradMag, outCandidates = candidates)

        // Hypothesis Family B2: Classic Adaptive Paperness Mask (proven on dark desks & printouts)
        val paperness = IntArray(total)
        val pHist = IntArray(256)
        var centerP = 0L; var centerPCount = 0
        var borderP = 0L; var borderPCount = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val r = sR[idx]; val g = sG[idx]; val b = sB[idx]
                val yVal = sL[idx]
                val sat = max(r, max(g, b)) - min(r, min(g, b))
                val warmBias = max(0, (r - b) - 12)
                val pScore = (yVal - (sat * 12) / 10 - (warmBias * 6) / 10).coerceIn(0, 255)
                paperness[idx] = pScore
                pHist[pScore]++
                if (x in cx0..cx1 && y in cy0..cy1) {
                    centerP += pScore
                    centerPCount++
                }
                if (x < 4 || x >= w - 4 || y < 4 || y >= h - 4) {
                    borderP += pScore
                    borderPCount++
                }
            }
        }
        val otsu = computeOtsuThreshold(pHist, total)
        val centerMean = (centerP / centerPCount.coerceAtLeast(1)).toInt()
        val borderMean = (borderP / borderPCount.coerceAtLeast(1)).toInt()
        val adaptiveCutoff = ((otsu * 46 + centerMean * 37 + borderMean * 17) / 100).coerceIn(72, 224)
        val paperMask = BooleanArray(total)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                paperMask[idx] = paperness[idx] >= adaptiveCutoff
            }
        }
        extractMaskCandidates(w, h, paperMask, gxArr, gyArr, gradMag, outCandidates = candidates)

        // Hypothesis Family B3: Center-vs-Border RGB Color Nearest-Prototype Mask (great on light/colored desks)
        if (paperBgDiff >= 24) {
            val protoMask = BooleanArray(total)
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    val r = sR[idx]; val g = sG[idx]; val b = sB[idx]
                    val distPaper = abs(r - paperR) + abs(g - paperG) + abs(b - paperB)
                    val distBg = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                    if (distPaper + 10 < distBg) {
                        protoMask[idx] = true
                    }
                }
            }
            extractMaskCandidates(w, h, protoMask, gxArr, gyArr, gradMag, outCandidates = candidates)
        }

        // Score all candidates using edge alignment + inside/outside RGB contrast + geometry
        var bestScore = -1f
        var bestQuad: Array<PointF2D>? = null
        val minEdgeThreshold = max(12f, strongEdgeThresh * 0.65f)

        for (rawPoly in candidates) {
            val ordered = orderQuadClockwise(rawPoly) ?: continue
            val candidateScore = scoreCandidateQuad(
                quad = ordered,
                w = w,
                h = h,
                gxArr = gxArr,
                gyArr = gyArr,
                gradMag = gradMag,
                sR = sR,
                sG = sG,
                sB = sB,
                minEdgeThreshold = minEdgeThreshold
            )
            if (candidateScore > bestScore) {
                bestScore = candidateScore
                bestQuad = ordered
            }
        }

        if (bestQuad == null || bestScore < 0.24f) {
            return NormalizedQuad.FULL_FRAME
        }

        // Final high-precision edge refinement pass on the winning quad
        val refined = refineQuadAlongEdges(bestQuad, w, h, gxArr, gyArr, gradMag, searchRadius = 5)
        val finalOrdered = orderQuadClockwise(refined ?: bestQuad) ?: bestQuad
        val finalScore = scoreCandidateQuad(
            quad = finalOrdered,
            w = w,
            h = h,
            gxArr = gxArr,
            gyArr = gyArr,
            gradMag = gradMag,
            sR = sR,
            sG = sG,
            sB = sB,
            minEdgeThreshold = minEdgeThreshold
        )
        val chosen = if (finalScore >= bestScore * 0.96f) finalOrdered else bestQuad

        val invW = 1f / (w - 1).coerceAtLeast(1)
        val invH = 1f / (h - 1).coerceAtLeast(1)
        return NormalizedQuad(
            tlX = (chosen[0].x * invW).coerceIn(0.005f, 0.995f),
            tlY = (chosen[0].y * invH).coerceIn(0.005f, 0.995f),
            trX = (chosen[1].x * invW).coerceIn(0.005f, 0.995f),
            trY = (chosen[1].y * invH).coerceIn(0.005f, 0.995f),
            brX = (chosen[2].x * invW).coerceIn(0.005f, 0.995f),
            brY = (chosen[2].y * invH).coerceIn(0.005f, 0.995f),
            blX = (chosen[3].x * invW).coerceIn(0.005f, 0.995f),
            blY = (chosen[3].y * invH).coerceIn(0.005f, 0.995f)
        ).clamped()
    }

    /**
     * Gradient-Guided Polarity-Aware Hough Line Detector & Orthogonal Pair Quad Builder.
     */
    private fun extractHoughCandidates(
        w: Int,
        h: Int,
        nmsMag: IntArray,
        gxArr: IntArray,
        gyArr: IntArray,
        strongEdgeThresh: Int,
        outCandidates: MutableList<Array<PointF2D>>
    ) {
        val numAngles = 90 // 2-degree steps over 0..180
        val angleStepDeg = 2f
        val maxRho = hypot(w.toDouble(), h.toDouble()).roundToInt() + 2
        val rhoStep = 2
        val rhoBins = (maxRho * 2) / rhoStep + 1
        val rhoZeroBin = maxRho / rhoStep

        val cosT = FloatArray(numAngles)
        val sinT = FloatArray(numAngles)
        for (a in 0 until numAngles) {
            val rad = (a * angleStepDeg) * (PI / 180.0)
            cosT[a] = cos(rad).toFloat()
            sinT[a] = sin(rad).toFloat()
        }

        val accSize = numAngles * rhoBins
        val posVotes = FloatArray(accSize)
        val negVotes = FloatArray(accSize)
        val radToBin = numAngles / PI.toFloat()

        for (y in 3 until h - 3) {
            val row = y * w
            for (x in 3 until w - 3) {
                val idx = row + x
                val g = nmsMag[idx]
                if (g < strongEdgeThresh) continue
                val gx = gxArr[idx].toFloat()
                val gy = gyArr[idx].toFloat()
                var ang = atan2(gy, gx)
                val polarity = if (ang >= 0f) 1 else -1
                if (ang < 0f) ang += PI.toFloat()
                val centerBin = ((ang * radToBin).roundToInt() % numAngles + numAngles) % numAngles
                val weight = min(g.toFloat(), 140f)

                for (da in -4..4) {
                    val aBin = ((centerBin + da) % numAngles + numAngles) % numAngles
                    val rho = x * cosT[aBin] + y * sinT[aBin]
                    val rBin = (rho / rhoStep).roundToInt() + rhoZeroBin
                    if (rBin in 0 until rhoBins) {
                        val accIdx = aBin * rhoBins + rBin
                        val aSignedCos = gx * cosT[aBin] + gy * sinT[aBin]
                        if (aSignedCos >= 0f || (da == 0 && polarity > 0)) {
                            posVotes[accIdx] += weight
                        } else {
                            negVotes[accIdx] += weight
                        }
                    }
                }
            }
        }

        val combined = FloatArray(accSize)
        for (i in 0 until accSize) {
            val p = posVotes[i]
            val n = negVotes[i]
            val tot = p + n
            if (tot > 0f) {
                // Polarity consistency bonus rewards true boundary steps over double-sided text strokes
                val consistency = 0.55f + 0.45f * (abs(p - n) / (tot + 1f))
                combined[i] = max(p, n) * consistency + min(p, n) * 0.25f
            }
        }

        val minVote = strongEdgeThresh * min(w, h) * 0.14f
        val peaks = ArrayList<HoughLine>(24)
        for (a in 0 until numAngles) {
            for (r in 1 until rhoBins - 1) {
                val v = combined[a * rhoBins + r]
                if (v < minVote) continue
                var isLocalMax = true
                for (da in -3..3) {
                    val aa = ((a + da) % numAngles + numAngles) % numAngles
                    for (dr in -4..4) {
                        if (da == 0 && dr == 0) continue
                        val rr = r + dr
                        if (rr in 0 until rhoBins && combined[aa * rhoBins + rr] > v) {
                            isLocalMax = false
                            break
                        }
                    }
                    if (!isLocalMax) break
                }
                if (isLocalMax) {
                    peaks.add(
                        HoughLine(
                            cosT = cosT[a],
                            sinT = sinT[a],
                            rho = ((r - rhoZeroBin) * rhoStep).toFloat(),
                            angleDeg = a * angleStepDeg,
                            score = v
                        )
                    )
                }
            }
        }

        if (peaks.size < 4) return
        peaks.sortByDescending { it.score }
        val topLines = peaks.take(14)

        // Collect parallel-ish opposite-side line pairs
        val cx = w * 0.5f
        val cy = h * 0.5f
        val minPairDist = min(w, h) * 0.16f
        data class LinePair(val l1: HoughLine, val l2: HoughLine, val meanAngle: Float, val score: Float)
        val pairs = ArrayList<LinePair>(32)

        for (i in topLines.indices) {
            val l1 = topLines[i]
            for (j in i + 1 until topLines.size) {
                val l2 = topLines[j]
                val rawDiff = abs(l1.angleDeg - l2.angleDeg)
                val angleDiff = min(rawDiff, 180f - rawDiff)
                if (angleDiff <= 30f) {
                    val d1 = l1.cosT * cx + l1.sinT * cy - l1.rho
                    val d2Signed = if (rawDiff > 90f) {
                        -(l2.cosT * cx + l2.sinT * cy - l2.rho)
                    } else {
                        l2.cosT * cx + l2.sinT * cy - l2.rho
                    }
                    if (abs(d1 - d2Signed) >= minPairDist) {
                        val a2Adjusted = if (rawDiff > 90f) {
                            if (l2.angleDeg > l1.angleDeg) l2.angleDeg - 180f else l2.angleDeg + 180f
                        } else {
                            l2.angleDeg
                        }
                        val meanA = (((l1.angleDeg + a2Adjusted) * 0.5f) % 180f + 180f) % 180f
                        pairs.add(LinePair(l1, l2, meanA, l1.score + l2.score))
                    }
                }
            }
        }

        if (pairs.size < 2) return
        pairs.sortByDescending { it.score }
        val topPairs = pairs.take(18)

        fun intersectHough(a: HoughLine, b: HoughLine): PointF2D? {
            val det = a.cosT * b.sinT - b.cosT * a.sinT
            if (abs(det) < 0.25f) return null
            val x = (a.rho * b.sinT - b.rho * a.sinT) / det
            val y = (a.cosT * b.rho - b.cosT * a.rho) / det
            return PointF2D(x, y)
        }

        for (i in topPairs.indices) {
            val pA = topPairs[i]
            for (j in i + 1 until topPairs.size) {
                val pB = topPairs[j]
                val rawDiff = abs(pA.meanAngle - pB.meanAngle)
                val orthoDiff = min(rawDiff, 180f - rawDiff)
                if (orthoDiff in 48f..132f) {
                    val c0 = intersectHough(pA.l1, pB.l1) ?: continue
                    val c1 = intersectHough(pA.l1, pB.l2) ?: continue
                    val c2 = intersectHough(pA.l2, pB.l2) ?: continue
                    val c3 = intersectHough(pA.l2, pB.l1) ?: continue
                    val marginX = w * 0.08f
                    val marginY = h * 0.08f
                    val pts = arrayOf(c0, c1, c2, c3)
                    if (pts.all { it.x in -marginX..(w + marginX) && it.y in -marginY..(h + marginY) }) {
                        outCandidates.add(
                            Array(4) { k ->
                                PointF2D(
                                    pts[k].x.coerceIn(1f, (w - 2).toFloat()),
                                    pts[k].y.coerceIn(1f, (h - 2).toFloat())
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Morphologically closes [rawMask] (5x5), isolates the dominant central connected component,
     * builds its Convex Hull, reduces the hull to 4 corners via minimum-area-loss simplification,
     * and generates both raw-hull-DP4 and Sobel-refined quad candidates.
     */
    private fun extractMaskCandidates(
        w: Int,
        h: Int,
        rawMask: BooleanArray,
        gxArr: IntArray,
        gyArr: IntArray,
        gradMag: IntArray,
        outCandidates: MutableList<Array<PointF2D>>
    ) {
        val total = w * h
        // 5x5 Closing (Dilate radius 2 -> Erode radius 2) to bridge internal text/headers cleanly
        val dil1 = BooleanArray(total)
        val dil2 = BooleanArray(total)
        val er1 = BooleanArray(total)
        val closed = BooleanArray(total)

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                dil1[idx] = rawMask[idx] || rawMask[idx - 1] || rawMask[idx + 1] || rawMask[idx - w] || rawMask[idx + w]
            }
        }
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                dil2[idx] = dil1[idx] || dil1[idx - 1] || dil1[idx + 1] || dil1[idx - w] || dil1[idx + w]
            }
        }
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                er1[idx] = dil2[idx] && dil2[idx - 1] && dil2[idx + 1] && dil2[idx - w] && dil2[idx + w]
            }
        }
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                closed[idx] = er1[idx] && er1[idx - 1] && er1[idx + 1] && er1[idx - w] && er1[idx + w]
            }
        }

        val labels = IntArray(total)
        val queue = IntArray(total)
        var currentLabel = 0
        var bestLabel = -1
        var bestScore = -1.0

        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val maxDist = hypot(centerX.toDouble(), centerY.toDouble()).toFloat().coerceAtLeast(1f)
        val cx0 = (w * 0.24f).toInt()
        val cx1 = (w * 0.76f).toInt()
        val cy0 = (h * 0.22f).toInt()
        val cy1 = (h * 0.78f).toInt()

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val startIdx = y * w + x
                if (closed[startIdx] && labels[startIdx] == 0) {
                    currentLabel++
                    var head = 0
                    var tail = 0
                    queue[tail++] = startIdx
                    labels[startIdx] = currentLabel

                    var count = 0
                    var sumX = 0L
                    var sumY = 0L
                    var centralHits = 0

                    while (head < tail) {
                        val curr = queue[head++]
                        val cy = curr / w
                        val cx = curr - cy * w
                        count++
                        sumX += cx
                        sumY += cy
                        if (cx in cx0..cx1 && cy in cy0..cy1) centralHits++

                        val n1 = curr - 1
                        if (cx > 1 && closed[n1] && labels[n1] == 0) {
                            labels[n1] = currentLabel; queue[tail++] = n1
                        }
                        val n2 = curr + 1
                        if (cx < w - 2 && closed[n2] && labels[n2] == 0) {
                            labels[n2] = currentLabel; queue[tail++] = n2
                        }
                        val n3 = curr - w
                        if (cy > 1 && closed[n3] && labels[n3] == 0) {
                            labels[n3] = currentLabel; queue[tail++] = n3
                        }
                        val n4 = curr + w
                        if (cy < h - 2 && closed[n4] && labels[n4] == 0) {
                            labels[n4] = currentLabel; queue[tail++] = n4
                        }
                    }

                    if (count in (total * 0.05).toInt()..(total * 0.94).toInt()) {
                        val meanX = sumX.toFloat() / count
                        val meanY = sumY.toFloat() / count
                        val distRatio = hypot((meanX - centerX).toDouble(), (meanY - centerY).toDouble()).toFloat() / maxDist
                        val centrality = (1.20f - distRatio).coerceAtLeast(0.15f)
                        val centralRatio = centralHits.toFloat() / count.toFloat()
                        val score = count.toDouble() * centrality * (0.45 + centralRatio)
                        if (score > bestScore) {
                            bestScore = score
                            bestLabel = currentLabel
                        }
                    }
                }
            }
        }

        if (bestLabel == -1) return

        val boundaryPts = ArrayList<HullPoint>(w * 2 + h * 2)
        for (y in 1 until h - 1) {
            val row = y * w
            var rowLeft = -1
            var rowRight = -1
            for (x in 1 until w - 1) {
                if (labels[row + x] == bestLabel) {
                    if (rowLeft == -1) rowLeft = x
                    rowRight = x
                }
            }
            if (rowLeft != -1) {
                boundaryPts.add(HullPoint(rowLeft, y))
                if (rowRight != rowLeft) boundaryPts.add(HullPoint(rowRight, y))
            }
        }
        if (boundaryPts.size < 8) return

        val hull = computeConvexHull(boundaryPts)
        if (hull.size < 4) return

        val dp4 = simplifyConvexHullToQuad(hull) ?: return
        outCandidates.add(dp4)

        val refined = refineQuadAlongEdges(dp4, w, h, gxArr, gyArr, gradMag, searchRadius = 9)
        if (refined != null) {
            outCandidates.add(refined)
        }
    }

    /**
     * Reduces a convex hull polygon cleanly to 4 corners by iteratively removing the vertex
     * whose triangle (prev, curr, next) encloses the smallest area. Works at any tilt angle.
     */
    private fun simplifyConvexHullToQuad(hull: List<HullPoint>): Array<PointF2D>? {
        if (hull.size < 4) return null
        val poly = hull.mapTo(ArrayList(hull.size)) { PointF2D(it.x.toFloat(), it.y.toFloat()) }
        while (poly.size > 4) {
            var minArea = Float.MAX_VALUE
            var removeIdx = 0
            val n = poly.size
            for (i in 0 until n) {
                val p = poly[(i - 1 + n) % n]
                val c = poly[i]
                val q = poly[(i + 1) % n]
                val triArea = abs((c.x - p.x) * (q.y - p.y) - (c.y - p.y) * (q.x - p.x))
                if (triArea < minArea) {
                    minArea = triArea
                    removeIdx = i
                }
            }
            poly.removeAt(removeIdx)
        }
        return poly.toTypedArray()
    }

    /**
     * For each of the 4 sides of [quad], searches +/- [searchRadius] px along the true
     * perpendicular edge normal for peak directional Sobel gradients, fits a singularity-free
     * 2D Total-Least-Squares line, and intersects adjacent edges into crisp sub-pixel corners.
     */
    private fun refineQuadAlongEdges(
        quad: Array<PointF2D>,
        w: Int,
        h: Int,
        gxArr: IntArray,
        gyArr: IntArray,
        gradMag: IntArray,
        searchRadius: Int
    ): Array<PointF2D>? {
        val ordered = orderQuadClockwise(quad) ?: return null
        val fittedLines = ArrayList<Line2D>(4)

        for (s in 0 until 4) {
            val a = ordered[s]
            val b = ordered[(s + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < 12f) return null
            // Inward normal for clockwise polygon on screen (Y-down): (-dy/len, dx/len)
            val nx = -dy / len
            val ny = dx / len

            val sampleCount = 32
            val ptsX = ArrayList<Float>(sampleCount)
            val ptsY = ArrayList<Float>(sampleCount)
            val ptsW = ArrayList<Float>(sampleCount)

            for (k in 0 until sampleCount) {
                val t = 0.10f + 0.80f * (k.toFloat() / (sampleCount - 1))
                val sx = a.x + dx * t
                val sy = a.y + dy * t
                var bestD = 0f
                var bestStrength = -1f
                for (d in -searchRadius..searchRadius) {
                    val px = (sx + nx * d).roundToInt()
                    val py = (sy + ny * d).roundToInt()
                    if (px in 2 until w - 2 && py in 2 until h - 2) {
                        val idx = py * w + px
                        val dirMag = abs(gxArr[idx] * nx + gyArr[idx] * ny)
                        val score = dirMag * 0.85f + gradMag[idx] * 0.15f - abs(d) * 0.6f
                        if (score > bestStrength) {
                            bestStrength = score
                            bestD = d.toFloat()
                        }
                    }
                }
                if (bestStrength >= 8f) {
                    ptsX.add((sx + nx * bestD).coerceIn(1f, (w - 2).toFloat()))
                    ptsY.add((sy + ny * bestD).coerceIn(1f, (h - 2).toFloat()))
                    ptsW.add(bestStrength.coerceAtMost(140f))
                }
            }

            if (ptsX.size < 5) {
                // Keep original edge line if weak
                val line = Line2D(nx, ny, -(nx * a.x + ny * a.y))
                fittedLines.add(line)
            } else {
                fittedLines.add(fitWeightedLineTLS(ptsX, ptsY, ptsW) ?: Line2D(nx, ny, -(nx * a.x + ny * a.y)))
            }
        }

        val c0 = intersectLines2D(fittedLines[3], fittedLines[0]) ?: return null
        val c1 = intersectLines2D(fittedLines[0], fittedLines[1]) ?: return null
        val c2 = intersectLines2D(fittedLines[1], fittedLines[2]) ?: return null
        val c3 = intersectLines2D(fittedLines[2], fittedLines[3]) ?: return null
        val marginX = w * 0.06f
        val marginY = h * 0.06f
        val out = arrayOf(c0, c1, c2, c3)
        if (out.any { it.x !in -marginX..(w + marginX) || it.y !in -marginY..(h + marginY) }) {
            return null
        }
        return Array(4) { i ->
            PointF2D(
                out[i].x.coerceIn(1f, (w - 2).toFloat()),
                out[i].y.coerceIn(1f, (h - 2).toFloat())
            )
        }
    }

    private fun fitWeightedLineTLS(
        xs: List<Float>,
        ys: List<Float>,
        ws: List<Float>
    ): Line2D? {
        var wSum = 0.0
        var mx = 0.0
        var my = 0.0
        for (i in xs.indices) {
            val wi = ws[i].toDouble()
            wSum += wi
            mx += wi * xs[i]
            my += wi * ys[i]
        }
        if (wSum < 1e-4) return null
        mx /= wSum
        my /= wSum

        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in xs.indices) {
            val wi = ws[i].toDouble()
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += wi * dx * dx
            syy += wi * dy * dy
            sxy += wi * dx * dy
        }
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        val a = -sin(theta).toFloat()
        val b = cos(theta).toFloat()
        val c = -(a * mx.toFloat() + b * my.toFloat())
        return Line2D(a, b, c)
    }

    private fun intersectLines2D(l1: Line2D, l2: Line2D): PointF2D? {
        val det = l1.a * l2.b - l2.a * l1.b
        if (abs(det) < 1e-4f) return null
        val x = (l1.b * l2.c - l2.b * l1.c) / det
        val y = (l1.c * l2.a - l2.c * l1.a) / det
        return PointF2D(x, y)
    }

    /**
     * Orders 4 vertices cleanly into clockwise `(TL, TR, BR, BL)` screen order and verifies
     * strict convexity. Returns null if degenerate or self-intersecting.
     */
    private fun orderQuadClockwise(pts: Array<PointF2D>): Array<PointF2D>? {
        if (pts.size != 4) return null
        var cx = 0f
        var cy = 0f
        for (p in pts) { cx += p.x; cy += p.y }
        cx *= 0.25f
        cy *= 0.25f

        val sorted = pts.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        // Verify strict clockwise convexity (positive cross product in screen Y-down coordinates)
        for (i in 0 until 4) {
            val a = sorted[i]
            val b = sorted[(i + 1) % 4]
            val c = sorted[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross <= 1f) return null
        }

        // Rotate indices so index 0 is Top-Left (minimum x + y)
        var tlIdx = 0
        var bestSum = Float.MAX_VALUE
        for (i in 0 until 4) {
            val s = sorted[i].x + sorted[i].y
            if (s < bestSum) {
                bestSum = s
                tlIdx = i
            }
        }
        return Array(4) { k -> sorted[(tlIdx + k) % 4] }
    }

    /**
     * Scores a candidate convex clockwise quadrilateral `(TL, TR, BR, BL)` by:
     * 1. Valid area & perspective interior angles (`42..138°`)
     * 2. Directional Sobel edge alignment along all 4 sides
     * 3. Inside-vs-outside RGB contrast (rejecting internal text boxes/tables & frame borders)
     */
    private fun scoreCandidateQuad(
        quad: Array<PointF2D>,
        w: Int,
        h: Int,
        gxArr: IntArray,
        gyArr: IntArray,
        gradMag: IntArray,
        sR: IntArray,
        sG: IntArray,
        sB: IntArray,
        minEdgeThreshold: Float
    ): Float {
        val totalArea = (w * h).toFloat().coerceAtLeast(1f)
        var shoelace = 0f
        for (i in 0 until 4) {
            val p = quad[i]
            val q = quad[(i + 1) % 4]
            shoelace += (p.x * q.y - q.x * p.y)
        }
        val polyArea = abs(shoelace) * 0.5f
        val areaRatio = polyArea / totalArea
        if (areaRatio !in 0.10f..0.95f) return -1f

        val minDim = min(w, h).toFloat()
        val edgeLens = FloatArray(4)
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            if (len < minDim * 0.16f) return -1f
            edgeLens[i] = len
        }
        if (max(edgeLens[0], edgeLens[2]) / min(edgeLens[0], edgeLens[2]).coerceAtLeast(1f) > 2.6f) return -1f
        if (max(edgeLens[1], edgeLens[3]) / min(edgeLens[1], edgeLens[3]).coerceAtLeast(1f) > 2.6f) return -1f

        var orthoPenalty = 0f
        for (i in 0 until 4) {
            val prev = quad[(i + 3) % 4]
            val curr = quad[i]
            val next = quad[(i + 1) % 4]
            val v1x = prev.x - curr.x; val v1y = prev.y - curr.y
            val v2x = next.x - curr.x; val v2y = next.y - curr.y
            val denom = (hypot(v1x.toDouble(), v1y.toDouble()) * hypot(v2x.toDouble(), v2y.toDouble())).toFloat().coerceAtLeast(1f)
            val cosAngle = ((v1x * v2x + v1y * v2y) / denom).coerceIn(-1f, 1f)
            // Angles outside ~42°..138° have |cos| > 0.743
            if (abs(cosAngle) > 0.743f) return -1f
            orthoPenalty += abs(cosAngle)
        }
        orthoPenalty *= 0.25f

        var minHitRatio = 1f
        var sumHitRatio = 0f
        var minContrast = 1f
        var sumContrast = 0f
        var borderHugEdges = 0

        val samplesPerEdge = 24
        for (s in 0 until 4) {
            val a = quad[s]
            val b = quad[(s + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = edgeLens[s]
            val nx = -dy / len
            val ny = dx / len

            var posSum = 0f
            var negSum = 0f
            var edgeHits = 0
            var contrastSum = 0f
            var contrastSamples = 0
            var nearBorderSamples = 0

            for (k in 0 until samplesPerEdge) {
                val t = 0.08f + 0.84f * (k.toFloat() / (samplesPerEdge - 1))
                val sx = a.x + dx * t
                val sy = a.y + dy * t

                if (sx < 2.5f || sx > w - 3.5f || sy < 2.5f || sy > h - 3.5f) {
                    nearBorderSamples++
                    continue
                }

                var bestAbsDir = 0f
                var bestSignedDir = 0f
                for (d in -2..2) {
                    val px = (sx + nx * d).roundToInt()
                    val py = (sy + ny * d).roundToInt()
                    if (px in 1 until w - 1 && py in 1 until h - 1) {
                        val idx = py * w + px
                        val signed = gxArr[idx] * nx + gyArr[idx] * ny
                        val absD = abs(signed)
                        if (absD > bestAbsDir) {
                            bestAbsDir = absD
                            bestSignedDir = signed
                        }
                    }
                }
                if (bestAbsDir >= minEdgeThreshold) {
                    edgeHits++
                    if (bestSignedDir >= 0f) posSum += bestAbsDir else negSum += bestAbsDir
                }

                val inX = (sx + nx * 6f).roundToInt()
                val inY = (sy + ny * 6f).roundToInt()
                val outX = (sx - nx * 6f).roundToInt()
                val outY = (sy - ny * 6f).roundToInt()
                if (inX in 0 until w && inY in 0 until h && outX in 0 until w && outY in 0 until h) {
                    val inIdx = inY * w + inX
                    val outIdx = outY * w + outX
                    val deltaRgb = abs(sR[inIdx] - sR[outIdx]) +
                        abs(sG[inIdx] - sG[outIdx]) +
                        abs(sB[inIdx] - sB[outIdx])
                    contrastSum += (deltaRgb / 220f).coerceAtMost(1f)
                    contrastSamples++
                }
            }

            if (nearBorderSamples > samplesPerEdge * 0.7f) {
                borderHugEdges++
            }

            val totalSigned = posSum + negSum
            val polarityFactor = if (totalSigned > 1f) abs(posSum - negSum) / totalSigned else 0f
            val hitRatio = (edgeHits.toFloat() / samplesPerEdge) * (0.55f + 0.45f * polarityFactor)
            val edgeContrast = if (contrastSamples > 0) contrastSum / contrastSamples else 0f

            minHitRatio = min(minHitRatio, hitRatio)
            sumHitRatio += hitRatio
            minContrast = min(minContrast, edgeContrast)
            sumContrast += edgeContrast
        }

        if (borderHugEdges >= 3) return -1f
        val meanHitRatio = sumHitRatio * 0.25f
        val meanContrast = sumContrast * 0.25f

        // Minimum verification gate: reject phantom rectangles & blank/uniform scenes
        if (meanHitRatio < 0.26f || minHitRatio < 0.14f || meanContrast < 0.045f) {
            return -1f
        }

        val edgeScore = 0.45f * minHitRatio + 0.55f * meanHitRatio
        val contrastScore = 0.45f * minContrast + 0.55f * meanContrast
        val areaPrior = 0.72f + 0.28f * areaRatio
        val orthoFactor = (1.0f - orthoPenalty * 0.30f).coerceAtLeast(0.65f)
        return (edgeScore * 0.55f + contrastScore * 0.45f) * areaPrior * orthoFactor
    }

    /**
     * Andrew's Monotone Chain 2D Convex Hull algorithm in O(N log N).
     */
    private fun computeConvexHull(points: List<HullPoint>): List<HullPoint> {
        if (points.size <= 3) return points
        val sorted = points.sorted()
        val hull = ArrayList<HullPoint>(sorted.size * 2)

        fun cross(o: HullPoint, a: HullPoint, b: HullPoint): Long {
            return (a.x - o.x).toLong() * (b.y - o.y).toLong() -
                (a.y - o.y).toLong() * (b.x - o.x).toLong()
        }

        // Lower hull
        for (pt in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0L) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(pt)
        }
        // Upper hull
        val lowerSize = hull.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val pt = sorted[i]
            while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0L) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(pt)
        }
        if (hull.size > 1) {
            hull.removeAt(hull.size - 1)
        }
        return hull
    }

    private fun computeOtsuThreshold(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var threshold = 140
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val betweenVar = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (betweenVar > maxVar) {
                maxVar = betweenVar
                threshold = t
            }
        }
        return threshold
    }

    /**
     * One-Euro Adaptive Velocity Smoother:
     * Scales smoothing cutoff dynamically by corner velocity so stationary documents lock with
     * near-zero micro-jitter (`alpha = 0.16`) while rapid camera motion tracks immediately (`alpha = 0.78`).
     */
    fun smoothQuad(current: NormalizedQuad, target: NormalizedQuad): NormalizedQuad {
        val velocity = (
            hypot((target.tlX - current.tlX).toDouble(), (target.tlY - current.tlY).toDouble()) +
                hypot((target.trX - current.trX).toDouble(), (target.trY - current.trY).toDouble()) +
                hypot((target.brX - current.brX).toDouble(), (target.brY - current.brY).toDouble()) +
                hypot((target.blX - current.blX).toDouble(), (target.blY - current.blY).toDouble())
            ).toFloat() * 0.25f

        if (velocity < 0.0045f) {
            return current
        }

        val alpha = (0.16f + velocity * 4.5f).coerceIn(0.16f, 0.78f)
        return NormalizedQuad(
            tlX = current.tlX + (target.tlX - current.tlX) * alpha,
            tlY = current.tlY + (target.tlY - current.tlY) * alpha,
            trX = current.trX + (target.trX - current.trX) * alpha,
            trY = current.trY + (target.trY - current.trY) * alpha,
            brX = current.brX + (target.brX - current.brX) * alpha,
            brY = current.brY + (target.brY - current.brY) * alpha,
            blX = current.blX + (target.blX - current.blX) * alpha,
            blY = current.blY + (target.blY - current.blY) * alpha
        ).clamped()
    }

    /**
     * Full non-destructive rendering pipeline at high resolution:
     * 1. Takes the immutable unrotated original Bitmap
     * 2. Applies 4-point projective perspective rectification with Auto 3D Perspective Aspect-Ratio Recovery
     * 3. Applies clockwise rotation ([rotationDegrees] = 0, 90, 180, 270)
     * 4. Applies the chosen high-definition shadow-free [filter]
     */
    fun processDocument(
        original: Bitmap,
        quad: NormalizedQuad,
        rotationDegrees: Int,
        filter: ScanFilter,
        filterIntensity: Float = 0.5f,
        maxDimension: Int = 3600
    ): Bitmap {
        val warped = warpPerspective(original, quad.clamped(), maxDimension)
        val rotated = rotateBitmap(warped, rotationDegrees)
        if (rotated !== warped && warped !== original && !warped.isRecycled) {
            warped.recycle()
        }
        val filtered = applyFilter(rotated, filter, filterIntensity)
        if (filtered !== rotated && rotated !== original && !rotated.isRecycled) {
            rotated.recycle()
        }
        return filtered
    }

    /**
     * Renders the perspective-cropped and rotated document without applying any enhancement filter
     * (i.e. ScanFilter.ORIGINAL on the cropped document bounds).
     */
    fun renderCroppedOriginal(
        original: Bitmap,
        quad: NormalizedQuad,
        rotationDegrees: Int = 0,
        maxDimension: Int = 2400
    ): Bitmap {
        val warped = warpPerspective(original, quad.clamped(), maxDimension)
        val rotated = rotateBitmap(warped, rotationDegrees)
        if (rotated !== warped && warped !== original && !warped.isRecycled) {
            warped.recycle()
        }
        return if (rotated === original) {
            copyBitmapArgb8888(original)
        } else {
            rotated
        }
    }

    private fun copyBitmapArgb8888(source: Bitmap): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val isHardware = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        if (!isHardware) {
            val px = IntArray(w * h)
            source.getPixels(px, 0, w, 0, 0, w, h)
            out.setPixels(px, 0, w, 0, 0, w, h)
        } else {
            Canvas(out).drawBitmap(source, 0f, 0f, null)
        }
        return out
    }

    /**
     * Performs 4-point perspective transformation from normalized source quadrilateral [quad]
     * onto a flat rectangular bitmap using Auto 3D Perspective Aspect-Ratio Recovery.
     */
    fun warpPerspective(
        source: Bitmap,
        quad: NormalizedQuad,
        maxDimension: Int = 3600
    ): Bitmap {
        val srcW = source.width.coerceAtLeast(2)
        val srcH = source.height.coerceAtLeast(2)

        if (quad.isFullFrame()) {
            val scale = min(1f, maxDimension.toFloat() / max(srcW, srcH).toFloat())
            val outW = (srcW * scale).roundToInt().coerceAtLeast(2)
            val outH = (srcH * scale).roundToInt().coerceAtLeast(2)
            return if (outW == srcW && outH == srcH) {
                if (source.config == Bitmap.Config.ARGB_8888) {
                    source
                } else {
                    copyBitmapArgb8888(source)
                }
            } else {
                Bitmap.createScaledBitmap(source, outW, outH, true)
            }
        }

        val x0 = quad.tlX * (srcW - 1)
        val y0 = quad.tlY * (srcH - 1)
        val x1 = quad.trX * (srcW - 1)
        val y1 = quad.trY * (srcH - 1)
        val x2 = quad.brX * (srcW - 1)
        val y2 = quad.brY * (srcH - 1)
        val x3 = quad.blX * (srcW - 1)
        val y3 = quad.blY * (srcH - 1)

        val (rawW, rawH) = recoverTrue3DDimensions(
            x0, y0, x1, y1, x2, y2, x3, y3,
            srcW.toFloat(), srcH.toFloat()
        )

        val scale = min(1f, maxDimension.toFloat() / max(rawW, rawH))
        val dstW = (rawW * scale).roundToInt().coerceIn(32, maxDimension)
        val dstH = (rawH * scale).roundToInt().coerceIn(32, maxDimension)

        val skiaResult = runCatching {
            val matrix = Matrix()
            val srcPts = floatArrayOf(x0, y0, x1, y1, x2, y2, x3, y3)
            val dstPts = floatArrayOf(
                0f, 0f,
                dstW.toFloat(), 0f,
                dstW.toFloat(), dstH.toFloat(),
                0f, dstH.toFloat()
            )
            if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
                val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(source, matrix, paint)
                val probe = out.getPixel(dstW / 2, dstH / 2)
                if ((probe ushr 24) != 0) {
                    out
                } else {
                    out.recycle()
                    null
                }
            } else {
                null
            }
        }.getOrNull()

        if (skiaResult != null) {
            return skiaResult
        }

        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val dstPixels = IntArray(dstW * dstH)

        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val sx = x0 - x1 + x2 - x3
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val sy = y0 - y1 + y2 - y3

        val denomG = (dx1 * dy2 - dx2 * dy1)
        val g: Float
        val hCoeff: Float
        if (abs(denomG) > 1e-6f) {
            g = (sx * dy2 - sy * dx2) / denomG
            hCoeff = (dx1 * sy - dy1 * sx) / denomG
        } else {
            g = 0f
            hCoeff = 0f
        }
        val a = x1 - x0 + g * x1
        val b = x3 - x0 + hCoeff * x3
        val c = x0
        val d = y1 - y0 + g * y1
        val e = y3 - y0 + hCoeff * y3
        val f = y0

        val invDstW = 1f / (dstW - 1).coerceAtLeast(1)
        val invDstH = 1f / (dstH - 1).coerceAtLeast(1)

        for (py in 0 until dstH) {
            val v = py * invDstH
            val rowOffset = py * dstW
            for (px in 0 until dstW) {
                val u = px * invDstW
                val wDenom = (g * u + hCoeff * v + 1f).coerceAtLeast(1e-5f)
                val srcX = ((a * u + b * v + c) / wDenom).coerceIn(0f, (srcW - 1).toFloat())
                val srcY = ((d * u + e * v + f) / wDenom).coerceIn(0f, (srcH - 1).toFloat())

                val ix = srcX.toInt().coerceIn(0, srcW - 2)
                val iy = srcY.toInt().coerceIn(0, srcH - 2)
                val fx = srcX - ix
                val fy = srcY - iy

                val idx00 = iy * srcW + ix
                val c00 = srcPixels[idx00]
                val c10 = srcPixels[idx00 + 1]
                val c01 = srcPixels[idx00 + srcW]
                val c11 = srcPixels[idx00 + srcW + 1]

                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy

                val r = (
                    ((c00 shr 16) and 0xFF) * w00 +
                        ((c10 shr 16) and 0xFF) * w10 +
                        ((c01 shr 16) and 0xFF) * w01 +
                        ((c11 shr 16) and 0xFF) * w11
                    ).toInt().coerceIn(0, 255)

                val gr = (
                    ((c00 shr 8) and 0xFF) * w00 +
                        ((c10 shr 8) and 0xFF) * w10 +
                        ((c01 shr 8) and 0xFF) * w01 +
                        ((c11 shr 8) and 0xFF) * w11
                    ).toInt().coerceIn(0, 255)

                val bl = (
                    (c00 and 0xFF) * w00 +
                        (c10 and 0xFF) * w10 +
                        (c01 and 0xFF) * w01 +
                        (c11 and 0xFF) * w11
                    ).toInt().coerceIn(0, 255)

                dstPixels[rowOffset + px] = (0xFF shl 24) or (r shl 16) or (gr shl 8) or bl
            }
        }

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        out.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return out
    }

    private fun recoverTrue3DDimensions(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        imgW: Float, imgH: Float
    ): Pair<Float, Float> {
        val topEdge = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        val bottomEdge = hypot((x2 - x3).toDouble(), (y2 - y3).toDouble()).toFloat()
        val leftEdge = hypot((x3 - x0).toDouble(), (y3 - y0).toDouble()).toFloat()
        val rightEdge = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()

        val maxW = max(topEdge, bottomEdge).coerceAtLeast(16f)
        val minW = min(topEdge, bottomEdge).coerceAtLeast(8f)
        val maxH = max(leftEdge, rightEdge).coerceAtLeast(16f)
        val minH = min(leftEdge, rightEdge).coerceAtLeast(8f)

        val taperW = (maxW / minW).coerceIn(1f, 2.2f)
        val taperH = (maxH / minH).coerceIn(1f, 2.2f)

        var correctedW = maxW * taperH.pow(0.58f)
        var correctedH = maxH * taperW.pow(0.58f)

        val cx = imgW * 0.5f
        val cy = imgH * 0.5f
        val f = max(imgW, imgH) * 0.92f

        val u0 = x0 - cx; val v0 = y0 - cy
        val u1 = x1 - cx; val v1 = y1 - cy
        val u2 = x2 - cx; val v2 = y2 - cy
        val u3 = x3 - cx; val v3 = y3 - cy

        val dx32 = u3 - u2; val dy32 = v3 - v2
        val dx12 = u1 - u2; val dy12 = v1 - v2
        val denom = dx32 * dy12 - dy32 * dx12

        if (abs(denom) > 1f) {
            val dx02 = u0 - u2; val dy02 = v0 - v2
            val k3 = (dx02 * dy12 - dy02 * dx12) / denom
            val k1 = (dx32 * dy02 - dy32 * dx02) / denom
            if (k1 in 0.4f..2.5f && k3 in 0.4f..2.5f) {
                val w3dX = k1 * u1 - u0
                val w3dY = k1 * v1 - v0
                val w3dZ = (k1 - 1f) * f
                val lenW3D = sqrt(w3dX * w3dX + w3dY * w3dY + w3dZ * w3dZ)

                val h3dX = k3 * u3 - u0
                val h3dY = k3 * v3 - v0
                val h3dZ = (k3 - 1f) * f
                val lenH3D = sqrt(h3dX * h3dX + h3dY * h3dY + h3dZ * h3dZ)

                if (lenW3D > 16f && lenH3D > 16f) {
                    val zhangAspect = (lenH3D / lenW3D).coerceIn(0.35f, 2.85f)
                    val trapAspect = (correctedH / correctedW).coerceIn(0.35f, 2.85f)
                    val finalAspect = 0.55f * zhangAspect + 0.45f * trapAspect
                    correctedH = correctedW * finalAspect
                }
            }
        }

        val maxSpan = max(max(topEdge, bottomEdge), max(leftEdge, rightEdge))
        val currentMax = max(correctedW, correctedH).coerceAtLeast(1f)
        if (maxSpan > currentMax) {
            val boost = maxSpan / currentMax
            correctedW *= boost
            correctedH *= boost
        }

        return correctedW.coerceAtLeast(32f) to correctedH.coerceAtLeast(32f)
    }

    fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        val norm = ((rotationDegrees % 360) + 360) % 360
        if (norm == 0) return source
        val isHardware = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        if (!isHardware && (norm == 90 || norm == 180 || norm == 270)) {
            val w = source.width.coerceAtLeast(1)
            val h = source.height.coerceAtLeast(1)
            val src = IntArray(w * h)
            source.getPixels(src, 0, w, 0, 0, w, h)
            val outW = if (norm == 180) w else h
            val outH = if (norm == 180) h else w
            val dst = IntArray(w * h)
            when (norm) {
                90 -> {
                    for (y in 0 until h) {
                        val rowOff = y * w
                        val dx = h - 1 - y
                        for (x in 0 until w) {
                            dst[x * outW + dx] = src[rowOff + x]
                        }
                    }
                }
                180 -> {
                    val last = w * h - 1
                    for (i in 0 until w * h) {
                        dst[last - i] = src[i]
                    }
                }
                270 -> {
                    for (y in 0 until h) {
                        val rowOff = y * w
                        for (x in 0 until w) {
                            val dy = w - 1 - x
                            dst[dy * outW + y] = src[rowOff + x]
                        }
                    }
                }
            }
            return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
                setPixels(dst, 0, outW, 0, 0, outW, outH)
            }
        }
        val matrix = Matrix().apply { postRotate(norm.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Next-Generation Document Processing Engine (surpassing Adobe Scan and CamScanner):
     * 1. Multi-scale background illumination surface estimation via downscaled grid (captures both
     *    slow room gradients and sharp local paper fold/crease shadows with < 1MB footprint).
     * 2. Authentic Paper Tint Extraction: preserves natural paper chromaticity (pastel invoices,
     *    yellow legal notepads, parchment) while completely neutralizing fold shadows & creases.
     * 3. Division Normalization (I_norm = (I / I_bg) * Tint_paper): completely flattens crease
     *    shadows and lighting drops without bleaching or blowing out fine print.
     * 4. Sauvola-inspired Soft-Knee Contrast Gating: protects subtle bank watermarks, government
     *    seals, stamps, and signatures from being clipped to white, while delivering rich dark text.
     * 5. Fine-tuned by [intensity] (0.0 .. 1.0, default 0.5): fine-tunes contrast steepness and unsharp mask.
     */
    fun applyFilter(source: Bitmap, filter: ScanFilter, intensity: Float = 0.5f): Bitmap {
        if (filter == ScanFilter.ORIGINAL) {
            return copyBitmapArgb8888(source)
        }
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        if (filter == ScanFilter.AI_ENHANCE) {
            return AiDocumentEnhancer.enhance(source, clampedIntensity)
        }

        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)


        // For MAGIC_COLOR, WHITEBOARD, CLEAR_BW, GRAYSCALE:
        // Estimate macro-illumination surface on a downsampled grid (max 256px)
        val gridMax = 256
        val gScale = min(1f, gridMax.toFloat() / max(w, h))
        val gw = (w * gScale).roundToInt().coerceAtLeast(16)
        val gh = (h * gScale).roundToInt().coerceAtLeast(16)
        val small = if (gw == w && gh == h) source else Bitmap.createScaledBitmap(source, gw, gh, true)

        val gTotal = gw * gh
        val gPixels = IntArray(gTotal)
        small.getPixels(gPixels, 0, gw, 0, 0, gw, gh)
        if (small !== source && !small.isRecycled) {
            small.recycle()
        }

        val sR = FloatArray(gTotal)
        val sG = FloatArray(gTotal)
        val sB = FloatArray(gTotal)
        val sL = FloatArray(gTotal)
        for (i in 0 until gTotal) {
            val c = gPixels[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            sR[i] = r
            sG[i] = g
            sB[i] = b
            sL[i] = (r * 77f + g * 150f + b * 29f) / 256f
        }

        // Morphological max filter on small grid to lift illumination above CAD/text strokes
        val rMax = max(3, min(gw, gh) / 24)
        val maxL = FloatArray(gTotal)
        val maxR = FloatArray(gTotal)
        val maxG = FloatArray(gTotal)
        val maxB = FloatArray(gTotal)

        val tempL = FloatArray(gTotal)
        val tempR = FloatArray(gTotal)
        val tempG = FloatArray(gTotal)
        val tempB = FloatArray(gTotal)

        for (y in 0 until gh) {
            val rowOff = y * gw
            for (x in 0 until gw) {
                var bestL = sL[rowOff + x]
                var bestR = sR[rowOff + x]
                var bestG = sG[rowOff + x]
                var bestB = sB[rowOff + x]
                val xStart = max(0, x - rMax)
                val xEnd = min(gw - 1, x + rMax)
                for (kx in xStart..xEnd) {
                    val lVal = sL[rowOff + kx]
                    if (lVal > bestL) {
                        bestL = lVal
                        bestR = sR[rowOff + kx]
                        bestG = sG[rowOff + kx]
                        bestB = sB[rowOff + kx]
                    }
                }
                tempL[rowOff + x] = bestL
                tempR[rowOff + x] = bestR
                tempG[rowOff + x] = bestG
                tempB[rowOff + x] = bestB
            }
        }

        for (x in 0 until gw) {
            for (y in 0 until gh) {
                var bestL = tempL[y * gw + x]
                var bestR = tempR[y * gw + x]
                var bestG = tempG[y * gw + x]
                var bestB = tempB[y * gw + x]
                val yStart = max(0, y - rMax)
                val yEnd = min(gh - 1, y + rMax)
                for (ky in yStart..yEnd) {
                    val lVal = tempL[ky * gw + x]
                    if (lVal > bestL) {
                        bestL = lVal
                        bestR = tempR[ky * gw + x]
                        bestG = tempG[ky * gw + x]
                        bestB = tempB[ky * gw + x]
                    }
                }
                val idx = y * gw + x
                maxL[idx] = bestL
                maxR[idx] = bestR
                maxG[idx] = bestG
                maxB[idx] = bestB
            }
        }

        // Wide Macro-Scale 2D Box Blur (covers 30-40% of page dimension)
        // Eliminates local room bubbles and halo rings around open spaces
        val rBlur = max(24, min(gw, gh) / 4)
        val bgL = FloatArray(gTotal)
        val bgR = FloatArray(gTotal)
        val bgG = FloatArray(gTotal)
        val bgB = FloatArray(gTotal)

        // Horizontal blur
        for (y in 0 until gh) {
            val rowOff = y * gw
            var sumL = 0f; var sumR = 0f; var sumG = 0f; var sumB = 0f
            val initialWindow = min(gw, rBlur + 1)
            for (x in 0 until initialWindow) {
                sumL += maxL[rowOff + x]
                sumR += maxR[rowOff + x]
                sumG += maxG[rowOff + x]
                sumB += maxB[rowOff + x]
            }
            for (x in 0 until gw) {
                val left = x - rBlur - 1
                val right = x + rBlur
                if (left >= 0) {
                    sumL -= maxL[rowOff + left]
                    sumR -= maxR[rowOff + left]
                    sumG -= maxG[rowOff + left]
                    sumB -= maxB[rowOff + left]
                }
                if (right < gw && right >= initialWindow) {
                    sumL += maxL[rowOff + right]
                    sumR += maxR[rowOff + right]
                    sumG += maxG[rowOff + right]
                    sumB += maxB[rowOff + right]
                }
                val count = (min(gw - 1, right) - max(0, left + 1) + 1).coerceAtLeast(1)
                val inv = 1f / count
                tempL[rowOff + x] = sumL * inv
                tempR[rowOff + x] = sumR * inv
                tempG[rowOff + x] = sumG * inv
                tempB[rowOff + x] = sumB * inv
            }
        }

        // Vertical blur
        for (x in 0 until gw) {
            var sumL = 0f; var sumR = 0f; var sumG = 0f; var sumB = 0f
            val initialWindow = min(gh, rBlur + 1)
            for (y in 0 until initialWindow) {
                val idx = y * gw + x
                sumL += tempL[idx]
                sumR += tempR[idx]
                sumG += tempG[idx]
                sumB += tempB[idx]
            }
            for (y in 0 until gh) {
                val top = y - rBlur - 1
                val bottom = y + rBlur
                if (top >= 0) {
                    val idx = top * gw + x
                    sumL -= tempL[idx]
                    sumR -= tempR[idx]
                    sumG -= tempG[idx]
                    sumB -= tempB[idx]
                }
                if (bottom < gh && bottom >= initialWindow) {
                    val idx = bottom * gw + x
                    sumL += tempL[idx]
                    sumR += tempR[idx]
                    sumG += tempG[idx]
                    sumB += tempB[idx]
                }
                val count = (min(gh - 1, bottom) - max(0, top + 1) + 1).coerceAtLeast(1)
                val inv = 1f / count
                val idx = y * gw + x
                bgL[idx] = sumL * inv
                bgR[idx] = sumR * inv
                bgG[idx] = sumG * inv
                bgB[idx] = sumB * inv
            }
        }

        // Authentic Paper Tint Extraction
        var paperRSum = 0.0; var paperGSum = 0.0; var paperBSum = 0.0; var paperCount = 0
        val centerLums = FloatArray(gTotal)
        for (i in 0 until gTotal) centerLums[i] = sL[i]
        centerLums.sort()
        val p40Lum = centerLums[(gTotal * 40) / 100]

        for (y in 2 until gh - 2) {
            val rowOff = y * gw
            for (x in 2 until gw - 2) {
                val idx = rowOff + x
                if (sL[idx] >= p40Lum) {
                    val diff = abs(sL[idx] - sL[idx - 1]) + abs(sL[idx] - sL[idx - gw])
                    if (diff < 14f) {
                        paperRSum += sR[idx]
                        paperGSum += sG[idx]
                        paperBSum += sB[idx]
                        paperCount++
                    }
                }
            }
        }
        val paperTintR = if (paperCount > 0) (paperRSum / paperCount).toFloat().coerceIn(60f, 255f) else 248f
        val paperTintG = if (paperCount > 0) (paperGSum / paperCount).toFloat().coerceIn(60f, 255f) else 248f
        val paperTintB = if (paperCount > 0) (paperBSum / paperCount).toFloat().coerceIn(60f, 255f) else 244f
        val tintSat = max(paperTintR, max(paperTintG, paperTintB)) - min(paperTintR, min(paperTintG, paperTintB))

        val contrastFactor = 0.72f + clampedIntensity * 0.58f

        val isColoredPaper = tintSat > 18f
        val magicPaperR = if (isColoredPaper) paperTintR else {
            val whiten = 0.88f + clampedIntensity * 0.12f
            (paperTintR * (1f - whiten) + 255f * whiten).coerceIn(248f, 255f)
        }
        val magicPaperG = if (isColoredPaper) paperTintG else {
            val whiten = 0.88f + clampedIntensity * 0.12f
            (paperTintG * (1f - whiten) + 255f * whiten).coerceIn(248f, 255f)
        }
        val magicPaperB = if (isColoredPaper) paperTintB else {
            val whiten = 0.88f + clampedIntensity * 0.12f
            (paperTintB * (1f - whiten) + 255f * whiten).coerceIn(248f, 255f)
        }

        // Bilinear Full-Resolution Processing
        val invW = (gw - 1).toFloat() / (w - 1).coerceAtLeast(1)
        val invH = (gh - 1).toFloat() / (h - 1).coerceAtLeast(1)

        when (filter) {
            ScanFilter.ORIGINAL, ScanFilter.AI_ENHANCE -> return source

            ScanFilter.MAGIC_COLOR -> {
                val kneeLow = 0.58f
                val kneeHigh = (0.86f - clampedIntensity * 0.06f).coerceIn(0.78f, 0.90f)

                for (y in 0 until h) {
                    val rowOff = y * w
                    val gyF = y * invH
                    val gy0 = gyF.toInt().coerceIn(0, gh - 2)
                    val gy1 = gy0 + 1
                    val fy = gyF - gy0
                    val rowG0 = gy0 * gw
                    val rowG1 = gy1 * gw

                    for (x in 0 until w) {
                        val gxF = x * invW
                        val gx0 = gxF.toInt().coerceIn(0, gw - 2)
                        val gx1 = gx0 + 1
                        val fx = gxF - gx0

                        val w00 = (1f - fx) * (1f - fy)
                        val w10 = fx * (1f - fy)
                        val w01 = (1f - fx) * fy
                        val w11 = fx * fy

                        val bL = bgL[rowG0 + gx0] * w00 + bgL[rowG0 + gx1] * w10 + bgL[rowG1 + gx0] * w01 + bgL[rowG1 + gx1] * w11
                        val bR = bgR[rowG0 + gx0] * w00 + bgR[rowG0 + gx1] * w10 + bgR[rowG1 + gx0] * w01 + bgR[rowG1 + gx1] * w11
                        val bG = bgG[rowG0 + gx0] * w00 + bgG[rowG0 + gx1] * w10 + bgG[rowG1 + gx0] * w01 + bgG[rowG1 + gx1] * w11
                        val bB = bgB[rowG0 + gx0] * w00 + bgB[rowG0 + gx1] * w10 + bgB[rowG1 + gx0] * w01 + bgB[rowG1 + gx1] * w11

                        val idx = rowOff + x
                        val c = pixels[idx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = (r * 77 + g * 150 + b * 29) shr 8

                        val ratioL = lum / bL.coerceAtLeast(20f)
                        val normR = (r / bR.coerceAtLeast(20f)) * magicPaperR
                        val normG = (g / bG.coerceAtLeast(20f)) * magicPaperG
                        val normB = (b / bB.coerceAtLeast(20f)) * magicPaperB

                        val sharpDelta = if (y in 1 until h - 1 && x in 1 until w - 1) {
                            val surr = (((pixels[idx - 1] shr 16) and 0xFF) + ((pixels[idx + 1] shr 16) and 0xFF) +
                                ((pixels[idx - w] shr 16) and 0xFF) + ((pixels[idx + w] shr 16) and 0xFF)) shr 2
                            ((r - surr) * (0.32f + clampedIntensity * 0.32f)).roundToInt().coerceIn(-24, 24)
                        } else 0

                        // C1-continuous smoothstep tone mapping across the entire page (zero splotches / zero hard cliffs)
                        val (outR, outG, outB) = when {
                            ratioL >= kneeHigh -> {
                                val t = ((ratioL - kneeHigh) / (1.08f - kneeHigh)).coerceIn(0f, 1f)
                                val s = t * t * (3f - 2f * t)
                                val rVal = (normR * (1f - s) + magicPaperR * s + sharpDelta).roundToInt().coerceIn(0, 255)
                                val gVal = (normG * (1f - s) + magicPaperG * s + sharpDelta).roundToInt().coerceIn(0, 255)
                                val bVal = (normB * (1f - s) + magicPaperB * s + sharpDelta).roundToInt().coerceIn(0, 255)
                                Triple(rVal, gVal, bVal)
                            }
                            ratioL >= kneeLow -> {
                                val t = (ratioL - kneeLow) / (kneeHigh - kneeLow)
                                val s = t * t * (3f - 2f * t)
                                val contrastDamp = 0.70f + contrastFactor * 0.30f
                                val darkScale = 0.65f + s * 0.35f * contrastDamp
                                val rVal = (normR * darkScale + sharpDelta).roundToInt().coerceIn(0, 255)
                                val gVal = (normG * darkScale + sharpDelta).roundToInt().coerceIn(0, 255)
                                val bVal = (normB * darkScale + sharpDelta).roundToInt().coerceIn(0, 255)
                                Triple(rVal, gVal, bVal)
                            }
                            else -> {
                                val t = (ratioL / kneeLow).coerceIn(0f, 1f)
                                val inkScale = (0.35f + t * 0.30f) * (1.1f - clampedIntensity * 0.2f)
                                val rVal = (normR * inkScale + sharpDelta).roundToInt().coerceIn(0, 255)
                                val gVal = (normG * inkScale + sharpDelta).roundToInt().coerceIn(0, 255)
                                val bVal = (normB * inkScale + sharpDelta).roundToInt().coerceIn(0, 255)
                                Triple(rVal, gVal, bVal)
                            }
                        }

                        val sat = max(outR, max(outG, outB)) - min(outR, min(outG, outB))
                        val avg = (outR + outG + outB) / 3
                        val finalR: Int; val finalG: Int; val finalB: Int
                        if (sat > 16 && avg < 232) {
                            val boost = 1.25f + clampedIntensity * 0.25f
                            finalR = (avg + (outR - avg) * boost).roundToInt().coerceIn(0, 255)
                            finalG = (avg + (outG - avg) * boost).roundToInt().coerceIn(0, 255)
                            finalB = (avg + (outB - avg) * boost).roundToInt().coerceIn(0, 255)
                        } else {
                            finalR = outR
                            finalG = outG
                            finalB = outB
                        }

                        pixels[idx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }

            ScanFilter.WHITEBOARD -> {
                // Shadow-proof pure white (#FFFFFF) background with crisp text and high readability
                for (y in 0 until h) {
                    val rowOff = y * w
                    val gyF = y * invH
                    val gy0 = gyF.toInt().coerceIn(0, gh - 2)
                    val gy1 = gy0 + 1
                    val fy = gyF - gy0
                    val rowG0 = gy0 * gw
                    val rowG1 = gy1 * gw

                    for (x in 0 until w) {
                        val gxF = x * invW
                        val gx0 = gxF.toInt().coerceIn(0, gw - 2)
                        val gx1 = gx0 + 1
                        val fx = gxF - gx0

                        val w00 = (1f - fx) * (1f - fy)
                        val w10 = fx * (1f - fy)
                        val w01 = (1f - fx) * fy
                        val w11 = fx * fy

                        val bL = bgL[rowG0 + gx0] * w00 + bgL[rowG0 + gx1] * w10 + bgL[rowG1 + gx0] * w01 + bgL[rowG1 + gx1] * w11

                        val idx = rowOff + x
                        val c = pixels[idx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = (r * 77 + g * 150 + b * 29) shr 8

                        val ratioL = lum / bL.coerceAtLeast(20f)
                        val drop = 1f - ratioL

                        val gray = when {
                            drop <= 0.12f -> 255 // Pure #FFFFFF white
                            drop >= 0.28f * (1.1f - clampedIntensity * 0.2f) -> 0 // Pure dark black
                            else -> {
                                val t = (drop - 0.12f) / (0.16f)
                                ((1f - t * t * (3f - 2f * t)) * 255f).roundToInt().coerceIn(0, 255)
                            }
                        }
                        pixels[idx] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                    }
                }
            }

            ScanFilter.CLEAR_BW -> {
                // High-clarity monochrome scan with watermark preservation
                for (y in 0 until h) {
                    val rowOff = y * w
                    val gyF = y * invH
                    val gy0 = gyF.toInt().coerceIn(0, gh - 2)
                    val gy1 = gy0 + 1
                    val fy = gyF - gy0
                    val rowG0 = gy0 * gw
                    val rowG1 = gy1 * gw

                    for (x in 0 until w) {
                        val gxF = x * invW
                        val gx0 = gxF.toInt().coerceIn(0, gw - 2)
                        val gx1 = gx0 + 1
                        val fx = gxF - gx0

                        val w00 = (1f - fx) * (1f - fy)
                        val w10 = fx * (1f - fy)
                        val w01 = (1f - fx) * fy
                        val w11 = fx * fy

                        val bL = bgL[rowG0 + gx0] * w00 + bgL[rowG0 + gx1] * w10 + bgL[rowG1 + gx0] * w01 + bgL[rowG1 + gx1] * w11

                        val idx = rowOff + x
                        val c = pixels[idx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = (r * 77 + g * 150 + b * 29) shr 8

                        val ratioL = lum / bL.coerceAtLeast(20f)
                        val sharpDelta = if (y in 1 until h - 1 && x in 1 until w - 1) {
                            val surr = (((pixels[idx - 1] shr 16) and 0xFF) + ((pixels[idx + 1] shr 16) and 0xFF) +
                                ((pixels[idx - w] shr 16) and 0xFF) + ((pixels[idx + w] shr 16) and 0xFF)) shr 2
                            ((lum - surr) * (0.32f + clampedIntensity * 0.32f)).roundToInt().coerceIn(-24, 24)
                        } else 0

                        val bw = when {
                            ratioL >= 0.93f -> 255
                            ratioL in 0.65f..0.93f -> {
                                val t = (ratioL - 0.65f) / (0.93f - 0.65f)
                                val softTone = (175f + t * 80f + sharpDelta).roundToInt().coerceIn(160, 255)
                                softTone
                            }
                            else -> {
                                val t = (ratioL / 0.65f)
                                ((t * 110f) * (1.1f - clampedIntensity * 0.2f) + sharpDelta).roundToInt().coerceIn(0, 255)
                            }
                        }
                        pixels[idx] = (0xFF shl 24) or (bw shl 16) or (bw shl 8) or bw
                    }
                }
            }

            ScanFilter.GRAYSCALE -> {
                // Shadow-normalized smooth grayscale retaining true tonal richness
                for (y in 0 until h) {
                    val rowOff = y * w
                    val gyF = y * invH
                    val gy0 = gyF.toInt().coerceIn(0, gh - 2)
                    val gy1 = gy0 + 1
                    val fy = gyF - gy0
                    val rowG0 = gy0 * gw
                    val rowG1 = gy1 * gw

                    for (x in 0 until w) {
                        val gxF = x * invW
                        val gx0 = gxF.toInt().coerceIn(0, gw - 2)
                        val gx1 = gx0 + 1
                        val fx = gxF - gx0

                        val w00 = (1f - fx) * (1f - fy)
                        val w10 = fx * (1f - fy)
                        val w01 = (1f - fx) * fy
                        val w11 = fx * fy

                        val bL = bgL[rowG0 + gx0] * w00 + bgL[rowG0 + gx1] * w10 + bgL[rowG1 + gx0] * w01 + bgL[rowG1 + gx1] * w11

                        val idx = rowOff + x
                        val c = pixels[idx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val lum = (r * 77 + g * 150 + b * 29) shr 8

                        val ratioL = lum / bL.coerceAtLeast(20f)
                        val normalized = (ratioL * 250f).coerceIn(0f, 255f)
                        val curved = (((normalized - 20f) / (245f - 20f)).coerceIn(0f, 1f).pow(1.0f + (clampedIntensity - 0.5f) * 0.4f) * 255f)
                            .roundToInt().coerceIn(0, 255)
                        pixels[idx] = (0xFF shl 24) or (curved shl 16) or (curved shl 8) or curved
                    }
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Generates a high-resolution synthetic document sheet on a dark desk surface for virtual
     * camera testing and automated checks.
     */
    fun generateSampleCapturedSheet(sheetIndex: Int, width: Int = 1440, height: Int = 1920): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val basePixels = IntArray(width * height)
        val deskColor = Color.rgb(34, 40, 38)
        val paperColor = Color.rgb(248, 245, 238)
        val inkColor = Color.rgb(20, 24, 22)
        val padX = (width * 0.08f).toInt()
        val padY = (height * 0.08f).toInt()
        for (y in 0 until height) {
            val inPaperY = y in padY until (height - padY)
            val isInkLineY = inPaperY && (y % 36 in 0..2) && y in (height / 5)..(height * 4 / 5)
            val row = y * width
            for (x in 0 until width) {
                val inPaperX = x in padX until (width - padX)
                val inInkLineX = x in (width / 6) until (width * 5 / 6)
                basePixels[row + x] = when {
                    isInkLineY && inInkLineX -> inkColor
                    inPaperY && inPaperX -> paperColor
                    else -> deskColor
                }
            }
        }
        bmp.setPixels(basePixels, 0, width, 0, 0, width, height)
        val canvas = Canvas(bmp)

        val skew = (sheetIndex % 3) * 18f
        val tlX = width * 0.09f + skew
        val tlY = height * 0.08f
        val trX = width * 0.91f
        val trY = height * 0.095f + skew * 0.5f
        val brX = width * 0.93f - skew * 0.4f
        val brY = height * 0.91f
        val blX = width * 0.07f
        val blY = height * 0.895f - skew * 0.5f

        val paperPath = Path().apply {
            moveTo(tlX, tlY)
            lineTo(trX, trY)
            lineTo(brX, brY)
            lineTo(blX, blY)
            close()
        }

        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(248, 245, 238)
            style = Paint.Style.FILL
        }
        canvas.drawRect(tlX, tlY, brX, brY, paperPaint)
        canvas.drawPath(paperPath, paperPaint)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(22, 20, 25, 22)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(width * 0.78f, height * 0.76f, width * 0.34f, shadowPaint)

        val tealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 94, 89)
            strokeWidth = 6f
        }
        val marginL = width * 0.16f
        val marginR = width * 0.84f
        canvas.drawLine(marginL, height * 0.20f, marginR, height * 0.208f, tealPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 32, 30)
            textSize = width * 0.042f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("কাগজপত্র ARCHIVE · SHEET #$sheetIndex", marginL, height * 0.17f, titlePaint)

        val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 84, 80)
            textSize = width * 0.032f
        }
        canvas.drawText("কাগজপত্র ডকুমেন্ট রেকর্ড • ফাইল ক্রমিক নং ১০$sheetIndex", marginL, height * 0.255f, subTitlePaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(42, 48, 45)
            textSize = width * 0.028f
        }
        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 204, 192)
            strokeWidth = 2f
        }

        val lines = listOf(
            "1. Document Inspection & Field Verification Record",
            "2. Preserves full-resolution original & 4-corner crop",
            "3. Supports Bengali (বাংলা) & English folder organization",
            "4. Filters: Original, Magic Color, Whiteboard, Clear B&W, Grayscale",
            "5. Verified Date: 2026-11-11 · Batch Sheet #$sheetIndex"
        )
        var yPos = height * 0.34f
        for (line in lines) {
            canvas.drawText(line, marginL, yPos, bodyPaint)
            canvas.drawLine(marginL, yPos + 22f, marginR, yPos + 22f, rulePaint)
            yPos += height * 0.085f
        }

        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(178, 52, 44)
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val stampRect = RectF(width * 0.58f, height * 0.72f, width * 0.82f, height * 0.83f)
        canvas.drawRoundRect(stampRect, 16f, 16f, stampPaint)
        val stampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(178, 52, 44)
            textSize = width * 0.027f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("VERIFIED STAMP", width * 0.605f, height * 0.785f, stampTextPaint)

        val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(36, 78, 152)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val sigPath = Path().apply {
            moveTo(marginL, height * 0.80f)
            quadTo(marginL + 80f, height * 0.75f, marginL + 155f, height * 0.81f)
            quadTo(marginL + 220f, height * 0.84f, marginL + 300f, height * 0.77f)
        }
        canvas.drawPath(sigPath, sigPaint)

        return bmp
    }

    /**
     * Strict 2D clockwise convexity + minimum edge/area validation in normalized [0..1] space.
     * Allows rotated quadrilaterals (unlike axis-aligned X/Y checks) while preventing self-intersection.
     */
    fun isValidConvexQuad(
        q: NormalizedQuad,
        minEdge: Float = 0.05f,
        minArea: Float = 0.0045f
    ): Boolean {
        val xs = floatArrayOf(q.tlX, q.trX, q.brX, q.blX)
        val ys = floatArrayOf(q.tlY, q.trY, q.brY, q.blY)
        for (i in 0 until 4) {
            if (xs[i] !in -0.0001f..1.0001f || ys[i] !in -0.0001f..1.0001f) return false
        }
        var signedArea2 = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val dx1 = xs[j] - xs[i]
            val dy1 = ys[j] - ys[i]
            val edgeLen = hypot(dx1.toDouble(), dy1.toDouble()).toFloat()
            if (edgeLen < minEdge) return false
            val dx2 = xs[k] - xs[j]
            val dy2 = ys[k] - ys[j]
            // Screen Y-down clockwise requires cross > 0 at every vertex
            val cross = dx1 * dy2 - dy1 * dx2
            if (cross <= 1e-4f) return false
            signedArea2 += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(signedArea2) * 0.5f >= minArea
    }
}
