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
 *    left-side shadows, vignetting, folded creases, and tape strips on neutral paper.
 * 2. Macro illumination surface + white-balanced chromaticity & local color-density map so
 *    photos, ID portraits, colored logos/banners, pastel highlights, and red/blue stamps &
 *    signatures preserve rich, natural, un-washed colors without hollowing or graying out.
 * 3. High-pass stroke-valley detection + crisp S-curve ink solidification & despeckle for
 *    razor-sharp black & colored text on clean #FFFFFF paper.
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

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Produces a crisp, shadow-free, color-accurate document bitmap from [source], tuned by
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
                // 1. Multi-Scale Paper & Illumination Analysis on 480px Grid
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

                // 1a. Global Paper White-Balance Estimation from smooth bright low-chroma pixels
                val lumCopy = sL.copyOf()
                lumCopy.sort()
                val p45Lum = lumCopy[(gTotal * 45) / 100]
                var pRSum = 0.0
                var pGSum = 0.0
                var pBSum = 0.0
                var pCount = 0
                for (y in 2 until gh - 2) {
                    val rowOff = y * gw
                    for (x in 2 until gw - 2) {
                        val idx = rowOff + x
                        val lv = sL[idx]
                        if (lv >= p45Lum && lv >= 120f) {
                            val r = sR[idx]
                            val g = sG[idx]
                            val b = sB[idx]
                            val sat = max(r, max(g, b)) - min(r, min(g, b))
                            val grad = kotlin.math.abs(lv - sL[idx - 1]) + kotlin.math.abs(lv - sL[idx - gw])
                            if (sat < 48f && grad < 16f) {
                                pRSum += r
                                pGSum += g
                                pBSum += b
                                pCount++
                            }
                        }
                    }
                }
                val paperR: Float
                val paperG: Float
                val paperB: Float
                val paperL: Float
                val wbGainR: Float
                val wbGainG: Float
                val wbGainB: Float
                if (pCount >= max(12, gTotal / 45)) {
                    paperR = (pRSum / pCount).toFloat().coerceIn(80f, 255f)
                    paperG = (pGSum / pCount).toFloat().coerceIn(80f, 255f)
                    paperB = (pBSum / pCount).toFloat().coerceIn(80f, 255f)
                    paperL = (paperR * 0.299f + paperG * 0.587f + paperB * 0.114f).coerceIn(80f, 255f)
                    wbGainR = (paperL / paperR).coerceIn(0.82f, 1.25f)
                    wbGainG = (paperL / paperG).coerceIn(0.82f, 1.25f)
                    wbGainB = (paperL / paperB).coerceIn(0.82f, 1.25f)
                } else {
                    // Full-bleed colorful photo/poster without neutral white paper: keep native color balance
                    val p85Lum = lumCopy[(gTotal * 85) / 100].coerceIn(140f, 248f)
                    paperR = p85Lum
                    paperG = p85Lum
                    paperB = p85Lum
                    paperL = p85Lum
                    wbGainR = 1f
                    wbGainG = 1f
                    wbGainB = 1f
                }

                // 1b. Tight Local Morphological Paper Surface on 480px Grid (bridges text while hugging shadows)
                val rMax = max(5, min(gw, gh) / 46)
                val tempL = FloatArray(gTotal)
                val maxL = FloatArray(gTotal)

                for (y in 0 until gh) {
                    val rowOff = y * gw
                    for (x in 0 until gw) {
                        var bestL = sL[rowOff + x]
                        val xStart = max(0, x - rMax)
                        val xEnd = min(gw - 1, x + rMax)
                        for (kx in xStart..xEnd) {
                            val lv = sL[rowOff + kx]
                            if (lv > bestL) bestL = lv
                        }
                        tempL[rowOff + x] = bestL
                    }
                }
                for (x in 0 until gw) {
                    for (y in 0 until gh) {
                        var bestL = tempL[y * gw + x]
                        val yStart = max(0, y - rMax)
                        val yEnd = min(gh - 1, y + rMax)
                        for (ky in yStart..yEnd) {
                            val lv = tempL[ky * gw + x]
                            if (lv > bestL) bestL = lv
                        }
                        maxL[y * gw + x] = bestL
                    }
                }
                val bgL = FloatArray(gTotal)
                boxBlur1(maxL, gw, gh, 1, tempL, bgL)

                // 1c. Macro Illumination Surface (spans photos/colored banners cleanly without shrinking inside them)
                val rMacro = max(16, min(gw, gh) / 12)
                for (y in 0 until gh) {
                    val rowOff = y * gw
                    for (x in 0 until gw) {
                        var bestL = sL[rowOff + x]
                        val xStart = max(0, x - rMacro)
                        val xEnd = min(gw - 1, x + rMacro)
                        for (kx in xStart..xEnd) {
                            val lv = sL[rowOff + kx]
                            if (lv > bestL) bestL = lv
                        }
                        tempL[rowOff + x] = bestL
                    }
                }
                for (x in 0 until gw) {
                    for (y in 0 until gh) {
                        var bestL = tempL[y * gw + x]
                        val yStart = max(0, y - rMacro)
                        val yEnd = min(gh - 1, y + rMacro)
                        for (ky in yStart..yEnd) {
                            val lv = tempL[ky * gw + x]
                            if (lv > bestL) bestL = lv
                        }
                        maxL[y * gw + x] = bestL
                    }
                }
                val macroBgL = FloatArray(gTotal)
                val rMacroBlur = max(12, min(gw, gh) / 14)
                boxBlur1(maxL, gw, gh, rMacroBlur, tempL, macroBgL)

                // 1d. White-Balanced Chroma Density Map (detects photos, portraits, & colorful graphics regions)
                val rawChromaGrid = FloatArray(gTotal)
                for (i in 0 until gTotal) {
                    val wr = (sR[i] * wbGainR).coerceIn(0f, 255f)
                    val wg = (sG[i] * wbGainG).coerceIn(0f, 255f)
                    val wb = (sB[i] * wbGainB).coerceIn(0f, 255f)
                    val cMax = max(wr, max(wg, wb))
                    val cMin = min(wr, min(wg, wb))
                    val wSat = cMax - cMin
                    rawChromaGrid[i] = if (wSat > 11f) {
                        val rel = wSat / cMax.coerceAtLeast(48f)
                        max(wSat, wSat * 0.45f + rel * 105f)
                    } else {
                        0f
                    }
                }
                val colorDensityGrid = FloatArray(gTotal)
                val rColorBlur = max(5, min(gw, gh) / 72)
                boxBlur1(rawChromaGrid, gw, gh, rColorBlur, tempL, colorDensityGrid)

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
                val contrastAmt = 0.10f + clampedIntensity * 0.18f

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
                        val mL = macroBgL[rowG0 + gx0] * w00 + macroBgL[rowG0 + gx1] * w10 + macroBgL[rowG1 + gx0] * w01 + macroBgL[rowG1 + gx1] * w11
                        val colDens = colorDensityGrid[rowG0 + gx0] * w00 + colorDensityGrid[rowG0 + gx1] * w10 + colorDensityGrid[rowG1 + gx0] * w01 + colorDensityGrid[rowG1 + gx1] * w11

                        val idx = rowOff + x
                        val c = pixels[idx]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF
                        val lumInt = lumBuf[idx].toInt() and 0xFF
                        val lum = lumInt.toFloat()

                        val safeBgL = bL.coerceAtLeast(28f)
                        val safeMacroL = max(mL, safeBgL).coerceAtLeast(36f)
                        val ratio = lum / safeBgL
                        val macroRatio = lum / safeMacroL
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
                        // while thin and bold B&W strokes keep 100% solid interiors and slender edges.
                        val sharpWeight = (valley / 0.058f).coerceIn(0f, 1f)
                        val solidFillWeight = (((0.26f - ratio) / 0.14f).coerceIn(0f, 1f)) * (((52f - lum) / 24f).coerceIn(0f, 1f))
                        val keepWeight = max(sharpWeight, solidFillWeight)
                        val modulatedDrop = drop * (0.06f + 0.94f * keepWeight)
                        val strokeSignal = max(modulatedDrop, valley * valleyGain)
                        val maxAllowedByDrop = drop * (1.08f + clampedIntensity * 0.22f)
                        val rawSignal = min(strokeSignal, maxAllowedByDrop)

                        val bwInk = if (rawSignal <= whiteCut) {
                            0f
                        } else {
                            val t = ((rawSignal - whiteCut) / (blackCut - whiteCut)).coerceIn(0f, 1f)
                            val s = t * t * (3f - 2f * t)
                            s.pow(inkGamma)
                        }
                        val bwGray = ((1f - bwInk) * 255f).roundToInt().coerceIn(0, 255)

                        // White-balance pixel & compute continuous color/photo preservation weights
                        val wr = (r * wbGainR).coerceIn(0f, 255f)
                        val wg = (g * wbGainG).coerceIn(0f, 255f)
                        val wb = (b * wbGainB).coerceIn(0f, 255f)
                        val wMax = max(wr, max(wg, wb))
                        val wMin = min(wr, min(wg, wb))
                        val wSat = wMax - wMin
                        val pixelChroma = if (wSat > 10f) {
                            val relChroma = wSat / wMax.coerceAtLeast(48f)
                            max(wSat, wSat * 0.45f + relChroma * 105f)
                        } else {
                            0f
                        }

                        val insideSafeMargin = x >= 4 && x < w - 4 && y >= 4 && y < h - 4
                        val pixelColorW = if (insideSafeMargin) smoothstep(14f, 40f, pixelChroma) else 0f
                        // Preserves solid midtones/darks inside photos, logos, & colored banners while
                        // letting bright white paper (macroRatio >= 0.94) bleach to #FFFFFF
                        val photoInteriorW = if (insideSafeMargin) {
                            smoothstep(20f, 42f, colDens) * smoothstep(0.95f, 0.78f, macroRatio)
                        } else {
                            0f
                        }
                        val colorBlend = max(pixelColorW, photoInteriorW)

                        if (colorBlend <= 0.01f) {
                            if (bwGray >= 255) {
                                pixels[idx] = -0x1
                            } else {
                                pixels[idx] = (0xFF shl 24) or (bwGray shl 16) or (bwGray shl 8) or bwGray
                            }
                        } else {
                            // Anchor illumination smoothly so dim shadows lift without blowing out large dark photos
                            val photoSafeMacroL = max(safeMacroL, paperL * 0.84f)
                            val strokeIllumL = safeMacroL * 0.45f + safeBgL * 0.55f
                            val illumL = strokeIllumL * (1f - photoInteriorW) + photoSafeMacroL * photoInteriorW
                            val scaleToPaper = (250f / illumL.coerceAtLeast(36f)).coerceIn(0.88f, 2.35f)

                            var nr = wr * scaleToPaper
                            var ng = wg * scaleToPaper
                            var nb = wb * scaleToPaper
                            val peak = max(nr, max(ng, nb))
                            if (peak > 255f) {
                                val down = 255f / peak
                                nr *= down
                                ng *= down
                                nb *= down
                            }

                            // Gentle perceptual S-curve for contrast + hue-locked vibrance
                            val nLum = (nr * 0.299f + ng * 0.587f + nb * 0.114f).coerceAtLeast(1f)
                            val u = (nLum / 255f).coerceIn(0f, 1f)
                            val sCurve = u * u * (3f - 2f * u)
                            val targetU = (u + contrastAmt * (sCurve - u)).coerceIn(0f, 1f)
                            val contrastGain = (targetU * 255f) / nLum
                            var cr = nr * contrastGain
                            var cg = ng * contrastGain
                            var cb = nb * contrastGain
                            val cPeak = max(cr, max(cg, cb))
                            if (cPeak > 255f) {
                                val down = 255f / cPeak
                                cr *= down
                                cg *= down
                                cb *= down
                            }

                            // Selective vibrance boosts muted colors gently without neon-clipping vivid ones
                            val cMax = max(cr, max(cg, cb))
                            val cMin = min(cr, min(cg, cb))
                            val satRatio = ((cMax - cMin) / 255f).coerceIn(0f, 1f)
                            val vibrance = (0.12f + clampedIntensity * 0.16f) * (1f - satRatio * 0.62f)
                            val avgL = cr * 0.299f + cg * 0.587f + cb * 0.114f
                            cr = (avgL + (cr - avgL) * (1f + vibrance)).coerceIn(0f, 255f)
                            cg = (avgL + (cg - avgL) * (1f + vibrance)).coerceIn(0f, 255f)
                            cb = (avgL + (cb - avgL) * (1f + vibrance)).coerceIn(0f, 255f)

                            // Crisp darkening only for thin colored text/stamps on white paper, never photos
                            if (bwInk > 0.20f && photoInteriorW < 0.35f) {
                                val inkDeepen = 1f - 0.14f * bwInk * (1f - photoInteriorW)
                                cr *= inkDeepen
                                cg *= inkDeepen
                                cb *= inkDeepen
                            }

                            val bwF = bwGray.toFloat()
                            val outR = (bwF * (1f - colorBlend) + cr * colorBlend).roundToInt().coerceIn(0, 255)
                            val outG = (bwF * (1f - colorBlend) + cg * colorBlend).roundToInt().coerceIn(0, 255)
                            val outB = (bwF * (1f - colorBlend) + cb * colorBlend).roundToInt().coerceIn(0, 255)
                            // Tag pixels with noticeable color (alpha 0xFE) so Pass 4 protects them from grayscale despeckle
                            val alphaTag = if (colorBlend >= 0.25f) 0xFE else 0xFF
                            pixels[idx] = (alphaTag shl 24) or (outR shl 16) or (outG shl 8) or outB
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

    private fun boxBlur1(
        input: FloatArray,
        gw: Int,
        gh: Int,
        radius: Int,
        tmp: FloatArray,
        output: FloatArray
    ) {
        for (y in 0 until gh) {
            val rowOff = y * gw
            var sum = 0f
            val initWin = min(gw, radius + 1)
            for (x in 0 until initWin) {
                sum += input[rowOff + x]
            }
            for (x in 0 until gw) {
                val left = x - radius - 1
                val right = x + radius
                if (left >= 0) sum -= input[rowOff + left]
                if (right < gw && right >= initWin) sum += input[rowOff + right]
                val inv = 1f / (min(gw - 1, right) - max(0, left + 1) + 1).coerceAtLeast(1)
                tmp[rowOff + x] = sum * inv
            }
        }

        for (x in 0 until gw) {
            var sum = 0f
            val initWin = min(gh, radius + 1)
            for (y in 0 until initWin) {
                sum += tmp[y * gw + x]
            }
            for (y in 0 until gh) {
                val top = y - radius - 1
                val bottom = y + radius
                if (top >= 0) sum -= tmp[top * gw + x]
                if (bottom < gh && bottom >= initWin) sum += tmp[bottom * gw + x]
                val inv = 1f / (min(gh - 1, bottom) - max(0, top + 1) + 1).coerceAtLeast(1)
                output[y * gw + x] = sum * inv
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
