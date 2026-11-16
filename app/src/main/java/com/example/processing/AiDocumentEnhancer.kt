package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * On-device offline AI document cleanup & shadow-removal engine ("AI Enhance").
 *
 * Combines:
 * 1. Tight multi-scale morphological paper surface tracking (480px grid) to eliminate steep
 *    left-side shadows, vignetting, folded creases, and tape strips.
 * 2. High-pass stroke-valley detection + back-page bleed-through suppression + crisp S-curve
 *    ink solidification & despeckle to produce clean, sharp, scan-ready white pages with rich
 *    solid strokes (while preserving vivid colored stamps/signatures).
 *
 * Works 100% offline across all 3 storage modes (Phone Only, PC Server Sync, Google Sign-In)
 * with zero native binary dependencies.
 */
object AiDocumentEnhancer {

    private const val TAG = "AiDocumentEnhancer"
    private const val MODEL_ASSET_NAME = "ai_enhance.onnx"
    private const val GRID_MAX_DIM = 480

    @Volatile
    private var appContext: Context? = null

    private val inferenceLock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isModelAssetPresent(context: Context? = appContext): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ctx.assets.openFd(MODEL_ASSET_NAME).use { true }
        }.getOrElse {
            runCatching {
                ctx.assets.list("")?.contains(MODEL_ASSET_NAME) == true
            }.getOrDefault(false)
        }
    }

    /**
     * Produces a crisp, shadow-free, scan-grade document bitmap from [source], tuned by
     * [intensity] in `[0f, 1f]` (default `0.5f`).
     */
    fun enhance(source: Bitmap, intensity: Float = 0.5f): Bitmap {
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val clampedIntensity = intensity.coerceIn(0f, 1f)
        val total = w * h

        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        return synchronized(inferenceLock) {
            try {
                // 1. Tight Multi-Scale Morphological Paper Surface (480px grid)
                val gScale = min(1f, GRID_MAX_DIM.toFloat() / max(w, h))
                val gw = (w * gScale).roundToInt().coerceAtLeast(16)
                val gh = (h * gScale).roundToInt().coerceAtLeast(16)
                val gridBmp = if (gw == w && gh == h) source else Bitmap.createScaledBitmap(source, gw, gh, true)
                val gTotal = gw * gh
                val gPixels = IntArray(gTotal)
                gridBmp.getPixels(gPixels, 0, gw, 0, 0, gw, gh)
                if (gridBmp !== source && !gridBmp.isRecycled) gridBmp.recycle()

                val sL = FloatArray(gTotal)
                val sR = FloatArray(gTotal)
                val sG = FloatArray(gTotal)
                val sB = FloatArray(gTotal)
                for (i in 0 until gTotal) {
                    val c = gPixels[i]
                    val r = ((c ushr 16) and 0xFF).toFloat()
                    val g = ((c ushr 8) and 0xFF).toFloat()
                    val b = (c and 0xFF).toFloat()
                    sR[i] = r
                    sG[i] = g
                    sB[i] = b
                    sL[i] = r * 0.299f + g * 0.587f + b * 0.114f
                }

                // 1. Tight Local Morphological Paper Surface on 480px Grid
                // Bridges character strokes while hugging steep left-side shadows & tape edges
                val rMax = max(5, min(gw, gh) / 46)
                val tempL = FloatArray(gTotal)
                val tempR = FloatArray(gTotal)
                val tempG = FloatArray(gTotal)
                val tempB = FloatArray(gTotal)
                val maxL = FloatArray(gTotal)
                val maxR = FloatArray(gTotal)
                val maxG = FloatArray(gTotal)
                val maxB = FloatArray(gTotal)

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
                            val idx = rowOff + kx
                            val lv = sL[idx]
                            if (lv > bestL) {
                                bestL = lv
                                bestR = sR[idx]
                                bestG = sG[idx]
                                bestB = sB[idx]
                            }
                        }
                        val outIdx = rowOff + x
                        tempL[outIdx] = bestL
                        tempR[outIdx] = bestR
                        tempG[outIdx] = bestG
                        tempB[outIdx] = bestB
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
                            val idx = ky * gw + x
                            val lv = tempL[idx]
                            if (lv > bestL) {
                                bestL = lv
                                bestR = tempR[idx]
                                bestG = tempG[idx]
                                bestB = tempB[idx]
                            }
                        }
                        val outIdx = y * gw + x
                        maxL[outIdx] = bestL
                        maxR[outIdx] = bestR
                        maxG[outIdx] = bestG
                        maxB[outIdx] = bestB
                    }
                }

                // Tight 1px box blur so local paper surface tracks steep shadow slopes with zero lag
                val rBlur = 1
                val bgL = FloatArray(gTotal)
                val bgR = FloatArray(gTotal)
                val bgG = FloatArray(gTotal)
                val bgB = FloatArray(gTotal)
                boxBlur4(maxL, maxR, maxG, maxB, gw, gh, rBlur, tempL, tempR, tempG, tempB, bgL, bgR, bgG, bgB)

                // 2. Full-resolution Multi-Scale Bilateral Ray-Maxima Stroke & Solid-Interior Extractor
                // Use ByteArray (1 byte/px vs 4 bytes/px) to save ~38 MB at 4096px
                var origLum: ByteArray? = ByteArray(total).also { arr ->
                    for (i in 0 until total) {
                        val c = pixels[i]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        arr[i] = ((r * 77 + g * 150 + b * 29) ushr 8).toByte()
                    }
                }
                val lumBuf = origLum!!

                // Adaptive white/black thresholds driven by intensity slider (0..1) with proportional anti-aliased edges
                val whiteCut = (0.165f - clampedIntensity * 0.040f).coerceIn(0.11f, 0.20f)
                val blackCut = (whiteCut + 0.340f - clampedIntensity * 0.070f).coerceIn(whiteCut + 0.20f, 0.56f)
                val valleyGain = 0.74f + clampedIntensity * 0.24f
                val inkGamma = (1.06f - clampedIntensity * 0.12f).coerceIn(0.92f, 1.15f)

                // 6-step ray distances from 1px up to wide bold-header stem width (~15-24px).
                // By taking `min(max along negative ray, max along positive ray)` independently along H, V, D1, D2
                // (without asymmetric distance mismatch or slopeGate blocking), every pixel inside bold headers
                // ("BD INTERIOR", headings, bold keywords) sees the paper on both sides of the stroke and stays
                // 100% solid black inside, while broad cast shadows have one side remaining dark (`valley = 0`).
                val minDim = min(w, h)
                val r0 = 1
                val r1 = 2
                val r2 = max(3, minDim / 240)
                val r3 = max(5, minDim / 130)
                val r4 = max(8, minDim / 80)
                val r5 = max(12, minDim / 52)

                val invW = (gw - 1).toFloat() / (w - 1).coerceAtLeast(1)
                val invH = (gh - 1).toFloat() / (h - 1).coerceAtLeast(1)

                for (y in 0 until h) {
                    val rowOff = y * w
                    val gyF = y * invH
                    val gy0 = gyF.toInt().coerceIn(0, max(0, gh - 2))
                    val gy1 = min(gh - 1, gy0 + 1)
                    val fy = gyF - gy0
                    val rowG0 = gy0 * gw
                    val rowG1 = gy1 * gw

                    val ym0 = max(0, y - r0) * w; val yp0 = min(h - 1, y + r0) * w
                    val ym1 = max(0, y - r1) * w; val yp1 = min(h - 1, y + r1) * w
                    val ym2 = max(0, y - r2) * w; val yp2 = min(h - 1, y + r2) * w
                    val ym3 = max(0, y - r3) * w; val yp3 = min(h - 1, y + r3) * w
                    val ym4 = max(0, y - r4) * w; val yp4 = min(h - 1, y + r4) * w
                    val ym5 = max(0, y - r5) * w; val yp5 = min(h - 1, y + r5) * w

                    for (x in 0 until w) {
                        val gxF = x * invW
                        val gx0 = gxF.toInt().coerceIn(0, max(0, gw - 2))
                        val gx1 = min(gw - 1, gx0 + 1)
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
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val lumInt = lumBuf[idx].toInt() and 0xFF
                        val lum = lumInt.toFloat()

                        val safeBgL = bL.coerceAtLeast(28f)
                        val ratio = lum / safeBgL
                        val drop = (1f - ratio).coerceIn(0f, 1f)

                        val xm0 = max(0, x - r0); val xp0 = min(w - 1, x + r0)
                        val xm1 = max(0, x - r1); val xp1 = min(w - 1, x + r1)
                        val xm2 = max(0, x - r2); val xp2 = min(w - 1, x + r2)
                        val xm3 = max(0, x - r3); val xp3 = min(w - 1, x + r3)
                        val xm4 = max(0, x - r4); val xp4 = min(w - 1, x + r4)
                        val xm5 = max(0, x - r5); val xp5 = min(w - 1, x + r5)

                        // Horizontal negative & positive ray maxima
                        var hNeg = max(lumBuf[rowOff + xm0].toInt() and 0xFF, lumBuf[rowOff + xm1].toInt() and 0xFF)
                        hNeg = max(hNeg, max(lumBuf[rowOff + xm2].toInt() and 0xFF, lumBuf[rowOff + xm3].toInt() and 0xFF))
                        hNeg = max(hNeg, max(lumBuf[rowOff + xm4].toInt() and 0xFF, lumBuf[rowOff + xm5].toInt() and 0xFF))

                        var hPos = max(lumBuf[rowOff + xp0].toInt() and 0xFF, lumBuf[rowOff + xp1].toInt() and 0xFF)
                        hPos = max(hPos, max(lumBuf[rowOff + xp2].toInt() and 0xFF, lumBuf[rowOff + xp3].toInt() and 0xFF))
                        hPos = max(hPos, max(lumBuf[rowOff + xp4].toInt() and 0xFF, lumBuf[rowOff + xp5].toInt() and 0xFF))
                        val effH = min(hNeg, hPos)

                        // Vertical negative & positive ray maxima
                        var vNeg = max(lumBuf[ym0 + x].toInt() and 0xFF, lumBuf[ym1 + x].toInt() and 0xFF)
                        vNeg = max(vNeg, max(lumBuf[ym2 + x].toInt() and 0xFF, lumBuf[ym3 + x].toInt() and 0xFF))
                        vNeg = max(vNeg, max(lumBuf[ym4 + x].toInt() and 0xFF, lumBuf[ym5 + x].toInt() and 0xFF))

                        var vPos = max(lumBuf[yp0 + x].toInt() and 0xFF, lumBuf[yp1 + x].toInt() and 0xFF)
                        vPos = max(vPos, max(lumBuf[yp2 + x].toInt() and 0xFF, lumBuf[yp3 + x].toInt() and 0xFF))
                        vPos = max(vPos, max(lumBuf[yp4 + x].toInt() and 0xFF, lumBuf[yp5 + x].toInt() and 0xFF))
                        val effV = min(vNeg, vPos)

                        // Diagonal \ negative & positive ray maxima
                        var daNeg = max(lumBuf[ym0 + xm0].toInt() and 0xFF, lumBuf[ym1 + xm1].toInt() and 0xFF)
                        daNeg = max(daNeg, max(lumBuf[ym2 + xm2].toInt() and 0xFF, lumBuf[ym3 + xm3].toInt() and 0xFF))
                        daNeg = max(daNeg, max(lumBuf[ym4 + xm4].toInt() and 0xFF, lumBuf[ym5 + xm5].toInt() and 0xFF))

                        var daPos = max(lumBuf[yp0 + xp0].toInt() and 0xFF, lumBuf[yp1 + xp1].toInt() and 0xFF)
                        daPos = max(daPos, max(lumBuf[yp2 + xp2].toInt() and 0xFF, lumBuf[yp3 + xp3].toInt() and 0xFF))
                        daPos = max(daPos, max(lumBuf[yp4 + xp4].toInt() and 0xFF, lumBuf[yp5 + xp5].toInt() and 0xFF))
                        val effDa = min(daNeg, daPos)

                        // Diagonal / negative & positive ray maxima
                        var dbNeg = max(lumBuf[ym0 + xp0].toInt() and 0xFF, lumBuf[ym1 + xp1].toInt() and 0xFF)
                        dbNeg = max(dbNeg, max(lumBuf[ym2 + xp2].toInt() and 0xFF, lumBuf[ym3 + xp3].toInt() and 0xFF))
                        dbNeg = max(dbNeg, max(lumBuf[ym4 + xp4].toInt() and 0xFF, lumBuf[ym5 + xp5].toInt() and 0xFF))

                        var dbPos = max(lumBuf[yp0 + xm0].toInt() and 0xFF, lumBuf[yp1 + xm1].toInt() and 0xFF)
                        dbPos = max(dbPos, max(lumBuf[yp2 + xm2].toInt() and 0xFF, lumBuf[yp3 + xm3].toInt() and 0xFF))
                        dbPos = max(dbPos, max(lumBuf[yp4 + xm4].toInt() and 0xFF, lumBuf[yp5 + xm5].toInt() and 0xFF))
                        val effDb = min(dbNeg, dbPos)

                        val ridgePeak = max(max(effH, effV), max(effDa, effDb)).toFloat()
                        val rawValley = ((ridgePeak - lum) / safeBgL).coerceIn(0f, 0.85f)
                        val shadowNoiseBoost = ((160f - safeBgL).coerceIn(0f, 90f) / 90f) * 0.022f
                        val noiseFloor = 0.052f + shadowNoiseBoost
                        val valley = if (rawValley > noiseFloor) rawValley - noiseFloor else 0f

                        // Soft-shadow gate: cast shadows & dim lighting attenuate cleanly to #FFFFFF,
                        // while thin and bold strokes keep 100% solid interiors and slender anti-aliased edges.
                        val sharpWeight = (valley / 0.058f).coerceIn(0f, 1f)
                        val solidFillWeight = (((0.26f - ratio) / 0.14f).coerceIn(0f, 1f)) * (((52f - lum) / 24f).coerceIn(0f, 1f))
                        val keepWeight = max(sharpWeight, solidFillWeight)
                        val modulatedDrop = drop * (0.06f + 0.94f * keepWeight)
                        val strokeSignal = max(modulatedDrop, valley * valleyGain)
                        val maxAllowedByDrop = drop * (1.08f + clampedIntensity * 0.22f)
                        val rawSignal = min(strokeSignal, maxAllowedByDrop)

                        // Check normalized color chroma relative to local paper (preserves vivid red/blue stamps & colored ink)
                        val nr = ((r / bR.coerceAtLeast(28f)) * 255f).coerceIn(0f, 255f)
                        val ng = ((g / bG.coerceAtLeast(28f)) * 255f).coerceIn(0f, 255f)
                        val nb = ((b / bB.coerceAtLeast(28f)) * 255f).coerceIn(0f, 255f)
                        val normSat = max(nr, max(ng, nb)) - min(nr, min(ng, nb))
                        val insideSafeMargin = x >= 4 && x < w - 4 && y >= 4 && y < h - 4
                        val isVividColorInk = insideSafeMargin && normSat >= 86f && ratio < 0.88f && (valley > 0.04f || drop > 0.20f)

                        if (rawSignal <= whiteCut && !isVividColorInk) {
                            pixels[idx] = -0x1 // Pure #FFFFFF
                        } else {
                            val effectiveSignal = if (isVividColorInk) max(rawSignal, whiteCut + (normSat - 70f) / 170f) else rawSignal
                            if (effectiveSignal <= whiteCut) {
                                pixels[idx] = -0x1
                            } else {
                                val t = ((effectiveSignal - whiteCut) / (blackCut - whiteCut)).coerceIn(0f, 1f)
                                val s = t * t * (3f - 2f * t)
                                val ink = s.pow(inkGamma)

                                if (isVividColorInk) {
                                    // Alpha 0xFE tags vivid color ink without allocating a BooleanArray(total)
                                    val boost = 1.28f
                                    val avg = (nr + ng + nb) / 3f
                                    val sr = (avg + (nr - avg) * boost).coerceIn(0f, 255f) * 0.90f
                                    val sg = (avg + (ng - avg) * boost).coerceIn(0f, 255f) * 0.90f
                                    val sb = (avg + (nb - avg) * boost).coerceIn(0f, 255f) * 0.90f
                                    val outR = (255f * (1f - ink) + sr * ink).roundToInt().coerceIn(0, 255)
                                    val outG = (255f * (1f - ink) + sg * ink).roundToInt().coerceIn(0, 255)
                                    val outB = (255f * (1f - ink) + sb * ink).roundToInt().coerceIn(0, 255)
                                    pixels[idx] = (0xFE shl 24) or (outR shl 16) or (outG shl 8) or outB
                                } else {
                                    val gray = ((1f - ink) * 255f).roundToInt().coerceIn(0, 255)
                                    pixels[idx] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                                }
                            }
                        }
                    }
                }
                // Allow origLum ByteArray to be reclaimed before Pass 4
                @Suppress("UNUSED_VALUE")
                origLum = null

                // 4. Clean-Scan Polish Pass: Remove disconnected dust/tape noise & solidify active pen strokes
                if (w >= 4 && h >= 4 && clampedIntensity >= 0.20f) {
                    polishInkAndDespeckle(pixels, w, h)
                }

                // Restore opaque 0xFF alpha on any tagged color-accent pixels
                for (i in 0 until total) {
                    pixels[i] = pixels[i] or (0xFF shl 24)
                }

                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                out.setPixels(pixels, 0, w, 0, 0, w, h)
                out
            } catch (t: Throwable) {
                Log.e(TAG, "AI Enhance processing failed", t)
                source.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
    }

    private fun boxBlur4(
        inL: FloatArray, inR: FloatArray, inG: FloatArray, inB: FloatArray,
        gw: Int, gh: Int, radius: Int,
        tmpL: FloatArray, tmpR: FloatArray, tmpG: FloatArray, tmpB: FloatArray,
        outL: FloatArray, outR: FloatArray, outG: FloatArray, outB: FloatArray
    ) {
        for (y in 0 until gh) {
            val rowOff = y * gw
            var sumL = 0f; var sumR = 0f; var sumG = 0f; var sumB = 0f
            val initWin = min(gw, radius + 1)
            for (x in 0 until initWin) {
                val idx = rowOff + x
                sumL += inL[idx]; sumR += inR[idx]; sumG += inG[idx]; sumB += inB[idx]
            }
            for (x in 0 until gw) {
                val left = x - radius - 1
                val right = x + radius
                if (left >= 0) {
                    val idx = rowOff + left
                    sumL -= inL[idx]; sumR -= inR[idx]; sumG -= inG[idx]; sumB -= inB[idx]
                }
                if (right < gw && right >= initWin) {
                    val idx = rowOff + right
                    sumL += inL[idx]; sumR += inR[idx]; sumG += inG[idx]; sumB += inB[idx]
                }
                val inv = 1f / (min(gw - 1, right) - max(0, left + 1) + 1).coerceAtLeast(1)
                val idx = rowOff + x
                tmpL[idx] = sumL * inv; tmpR[idx] = sumR * inv; tmpG[idx] = sumG * inv; tmpB[idx] = sumB * inv
            }
        }

        for (x in 0 until gw) {
            var sumL = 0f; var sumR = 0f; var sumG = 0f; var sumB = 0f
            val initWin = min(gh, radius + 1)
            for (y in 0 until initWin) {
                val idx = y * gw + x
                sumL += tmpL[idx]; sumR += tmpR[idx]; sumG += tmpG[idx]; sumB += tmpB[idx]
            }
            for (y in 0 until gh) {
                val top = y - radius - 1
                val bottom = y + radius
                if (top >= 0) {
                    val idx = top * gw + x
                    sumL -= tmpL[idx]; sumR -= tmpR[idx]; sumG -= tmpG[idx]; sumB -= tmpB[idx]
                }
                if (bottom < gh && bottom >= initWin) {
                    val idx = bottom * gw + x
                    sumL += tmpL[idx]; sumR += tmpR[idx]; sumG += tmpG[idx]; sumB += tmpB[idx]
                }
                val inv = 1f / (min(gh - 1, bottom) - max(0, top + 1) + 1).coerceAtLeast(1)
                val idx = y * gw + x
                outL[idx] = sumL * inv; outR[idx] = sumR * inv; outG[idx] = sumG * inv; outB[idx] = sumB * inv
            }
        }
    }

    /**
     * Despeckle isolated dust/tape-noise components via fast primitive 8-connected BFS and
     * lightly solidify active pen strokes so thin ballpoint lines render crisp and unbroken on #FFFFFF.
     * Uses ByteArray luminance and a dynamically growing BFS queue to keep 4096px heap minimal.
     */
    private fun polishInkAndDespeckle(
        pixels: IntArray,
        w: Int,
        h: Int
    ) {
        val total = w * h
        val lum = ByteArray(total)
        for (i in 0 until total) {
            val c = pixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            lum[i] = ((r * 77 + g * 150 + b * 29) ushr 8).toByte()
        }

        run {
            // Pass 1: Connected-component despeckle scoped so visited/queue become reclaimable before Pass 2
            val tinyAreaLimit = max(2, total / 600_000)
            val visited = ByteArray(total)
            var queue = IntArray(min(total, 4096))

            for (start in 0 until total) {
                if (visited[start] != 0.toByte() ||
                    ((pixels[start] ushr 24) and 0xFF) == 0xFE ||
                    (lum[start].toInt() and 0xFF) >= 242
                ) continue

                var qHead = 0
                var qTail = 0
                visited[start] = 1
                queue[qTail++] = start

                var minLum = 255
                var sumLum = 0L
                var minX = w
                var maxX = -1
                var minY = h
                var maxY = -1

                while (qHead < qTail) {
                    val curIdx = queue[qHead++]
                    val lv = lum[curIdx].toInt() and 0xFF
                    if (lv < minLum) minLum = lv
                    sumLum += lv

                    val cx = curIdx % w
                    val cy = curIdx / w
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val yMin = max(0, cy - 1)
                    val yMax = min(h - 1, cy + 1)
                    val xMin = max(0, cx - 1)
                    val xMax = min(w - 1, cx + 1)
                    for (ny in yMin..yMax) {
                        val nRow = ny * w
                        for (nx in xMin..xMax) {
                            val nIdx = nRow + nx
                            if (visited[nIdx] == 0.toByte() &&
                                ((pixels[nIdx] ushr 24) and 0xFF) != 0xFE &&
                                (lum[nIdx].toInt() and 0xFF) < 242
                            ) {
                                visited[nIdx] = 1
                                if (qTail == queue.size) {
                                    queue = queue.copyOf(min(total, queue.size * 2))
                                }
                                queue[qTail++] = nIdx
                            }
                        }
                    }
                }

                val area = qTail
                val avgLum = (sumLum / area).toInt()
                val touchesBorder = minX == 0 || maxX == w - 1 || minY == 0 || maxY == h - 1
                val isEdgeSliver =
                    area <= 380 && (
                        (minX == 0 && maxX <= 10) ||
                            (maxX == w - 1 && minX >= w - 11) ||
                            (minY == 0 && maxY <= 8) ||
                            (maxY == h - 1 && minY >= h - 9)
                        )
                val isTapeSmudgePatch =
                    (area <= 650 && avgLum > 160 && minLum > 82) ||
                        ((minY <= 5 || maxY >= h - 6) && area <= 1050 && minLum > 38 && avgLum > 120)
                val isNoiseSpeck =
                    area <= 2 ||
                        (area <= tinyAreaLimit && minLum > 18) ||
                        (area <= 48 && avgLum > 110 && minLum > 28) ||
                        (area <= 260 && avgLum > 148 && minLum > 72) ||
                        (touchesBorder && area <= 85 && minLum > 38) ||
                        isTapeSmudgePatch ||
                        isEdgeSliver

                if (isNoiseSpeck) {
                    for (k in 0 until area) {
                        val pIdx = queue[k]
                        pixels[pIdx] = -0x1
                        lum[pIdx] = 255.toByte()
                    }
                }
            }
        }

        // Pass 2 reads clean immutable `lum` ByteArray (already updated in-place during Pass 1) and writes only to `pixels`
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (((pixels[idx] ushr 24) and 0xFF) == 0xFE) continue
                val cur = lum[idx].toInt() and 0xFF
                if (cur >= 242) continue

                val n0 = lum[idx - w - 1].toInt() and 0xFF
                val n1 = lum[idx - w].toInt() and 0xFF
                val n2 = lum[idx - w + 1].toInt() and 0xFF
                val n3 = lum[idx - 1].toInt() and 0xFF
                val n4 = lum[idx + 1].toInt() and 0xFF
                val n5 = lum[idx + w - 1].toInt() and 0xFF
                val n6 = lum[idx + w].toInt() and 0xFF
                val n7 = lum[idx + w + 1].toInt() and 0xFF

                var whiteNeighbors = 0
                var darkNeighbors = 0
                var strongInkNeighbors = 0

                if (n0 >= 238) whiteNeighbors++; if (n0 <= 175) darkNeighbors++; if (n0 <= 85) strongInkNeighbors++
                if (n1 >= 238) whiteNeighbors++; if (n1 <= 175) darkNeighbors++; if (n1 <= 85) strongInkNeighbors++
                if (n2 >= 238) whiteNeighbors++; if (n2 <= 175) darkNeighbors++; if (n2 <= 85) strongInkNeighbors++
                if (n3 >= 238) whiteNeighbors++; if (n3 <= 175) darkNeighbors++; if (n3 <= 85) strongInkNeighbors++
                if (n4 >= 238) whiteNeighbors++; if (n4 <= 175) darkNeighbors++; if (n4 <= 85) strongInkNeighbors++
                if (n5 >= 238) whiteNeighbors++; if (n5 <= 175) darkNeighbors++; if (n5 <= 85) strongInkNeighbors++
                if (n6 >= 238) whiteNeighbors++; if (n6 <= 175) darkNeighbors++; if (n6 <= 85) strongInkNeighbors++
                if (n7 >= 238) whiteNeighbors++; if (n7 <= 175) darkNeighbors++; if (n7 <= 85) strongInkNeighbors++

                if (whiteNeighbors >= 7 || (cur > 175 && darkNeighbors == 0)) {
                    pixels[idx] = -0x1
                } else if (cur <= 56 && strongInkNeighbors >= 6) {
                    // Deepen ONLY the deep interior of thick fills, never touching 1-3px strokes or anti-aliased edges
                    val crispCore = (cur * 0.80f).roundToInt().coerceIn(0, 56)
                    pixels[idx] = (0xFF shl 24) or (crispCore shl 16) or (crispCore shl 8) or crispCore
                } else if (cur in 175..244 && whiteNeighbors >= 4) {
                    // Gently trim outer grey halo pixels along edges so thin strokes stay slender and un-bolded
                    val trimmed = (cur * 0.42f + 255f * 0.58f).roundToInt().coerceIn(185, 255)
                    if (trimmed >= 230) {
                        pixels[idx] = -0x1
                    } else {
                        pixels[idx] = (0xFF shl 24) or (trimmed shl 16) or (trimmed shl 8) or trimmed
                    }
                }
            }
        }
    }
}
