package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 100% Offline Context-Aware Document Smart Eraser ("Smart Eraser").
 *
 * Heals unwanted lines, handwriting, ruler marks, punch holes, and smudges immediately when
 * the user lifts their finger:
 * 1. Extracts only the tight bounding box around the brushed stroke for sub-30ms interactive speed.
 * 2. Filters boundary ring samples by local paper percentile so dark ink lines crossing the brush
 *    boundary never bleed into the healed region.
 * 3. Runs coarse-to-fine harmonic (Laplace) surface relaxation across the stroke mask to match
 *    local illumination gradients and cast shadows seamlessly.
 * 4. Synthesizes subtle local paper micro-grain and feathers the brush edge for an invisible heal.
 */
object SmartDocumentEraser {

    /**
     * Erases a single continuous brush stroke defined by [normalizedPoints] (each in `0f..1f`
     * relative to [source] width and height) with [normalizedRadius] (relative to `min(width, height)`).
     */
    fun eraseStroke(
        source: Bitmap,
        normalizedPoints: List<Offset>,
        normalizedRadius: Float
    ): Bitmap {
        if (normalizedPoints.isEmpty()) return source
        val w = source.width
        val h = source.height
        if (w < 4 || h < 4) return source

        val minDim = min(w, h).toFloat()
        val radiusPx = (normalizedRadius.coerceIn(0.004f, 0.15f) * minDim).coerceIn(3f, 180f)
        val featherPx = (radiusPx * 0.36f).coerceIn(2.2f, 11f)
        val fullRadiusPx = radiusPx + featherPx
        val ringPadPx = max(12, (radiusPx * 1.15f).roundToInt().coerceAtMost(64))

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (pt in normalizedPoints) {
            val px = pt.x.coerceIn(0f, 1f) * (w - 1)
            val py = pt.y.coerceIn(0f, 1f) * (h - 1)
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
        }

        val totalPad = (fullRadiusPx + ringPadPx + 4f).roundToInt()
        val bx0 = (minX.roundToInt() - totalPad).coerceIn(0, w - 1)
        val by0 = (minY.roundToInt() - totalPad).coerceIn(0, h - 1)
        val bx1 = (maxX.roundToInt() + totalPad).coerceIn(0, w - 1)
        val by1 = (maxY.roundToInt() + totalPad).coerceIn(0, h - 1)

        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        if (bw < 3 || bh < 3) return source

        // 1. Compute analytical feathered capsule mask directly from polyline segments (zero Bitmap/Canvas allocation)
        val nPts = normalizedPoints.size
        val segX = FloatArray(nPts)
        val segY = FloatArray(nPts)
        for (i in 0 until nPts) {
            segX[i] = normalizedPoints[i].x.coerceIn(0f, 1f) * (w - 1) - bx0
            segY[i] = normalizedPoints[i].y.coerceIn(0f, 1f) * (h - 1) - by0
        }

        val bTotal = bw * bh
        val smoothAlpha = FloatArray(bTotal)
        val innerR = (radiusPx - featherPx * 0.35f).coerceAtLeast(1f)
        val outerR = fullRadiusPx.coerceAtLeast(innerR + 1f)
        val outerRSq = outerR * outerR
        val invFeather = 1f / (outerR - innerR).coerceAtLeast(0.5f)
        var anyMasked = false

        for (py in 0 until bh) {
            val rowOff = py * bw
            val yf = py.toFloat()
            for (px in 0 until bw) {
                val xf = px.toFloat()
                var minDistSq = Float.MAX_VALUE
                if (nPts == 1) {
                    val dx = xf - segX[0]
                    val dy = yf - segY[0]
                    minDistSq = dx * dx + dy * dy
                } else {
                    for (i in 0 until nPts - 1) {
                        val ax = segX[i]
                        val ay = segY[i]
                        val bx = segX[i + 1]
                        val by = segY[i + 1]
                        val abx = bx - ax
                        val aby = by - ay
                        val lenSq = abx * abx + aby * aby
                        val t = if (lenSq > 1e-5f) {
                            (((xf - ax) * abx + (yf - ay) * aby) / lenSq).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val cx = ax + t * abx
                        val cy = ay + t * aby
                        val dx = xf - cx
                        val dy = yf - cy
                        val dSq = dx * dx + dy * dy
                        if (dSq < minDistSq) minDistSq = dSq
                    }
                }
                if (minDistSq < outerRSq) {
                    val d = sqrt(minDistSq)
                    val a = if (d <= innerR) {
                        1f
                    } else {
                        val u = ((outerR - d) * invFeather).coerceIn(0f, 1f)
                        u * u * (3f - 2f * u)
                    }
                    if (a > 0.005f) {
                        smoothAlpha[rowOff + px] = a
                        anyMasked = true
                    }
                }
            }
        }
        if (!anyMasked) return source

        // 2. Read ROI pixels from source bitmap
        val roiPixels = IntArray(bTotal)
        source.getPixels(roiPixels, 0, bw, bx0, by0, bw, bh)

        val origR = FloatArray(bTotal)
        val origG = FloatArray(bTotal)
        val origB = FloatArray(bTotal)
        val origL = FloatArray(bTotal)
        for (i in 0 until bTotal) {
            val c = roiPixels[i]
            val r = ((c ushr 16) and 0xFF).toFloat()
            val g = ((c ushr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            origR[i] = r
            origG[i] = g
            origB[i] = b
            origL[i] = r * 0.299f + g * 0.587f + b * 0.114f
        }

        // 3. Collect unmasked boundary-ring luminance statistics (median/65th percentile rejects dark ink
        //    without biasing toward bright highlights, preserving the exact local paper shade!)
        val ringLums = FloatArray(bTotal)
        var ringCount = 0
        for (i in 0 until bTotal) {
            if (smoothAlpha[i] <= 0.04f) {
                ringLums[ringCount++] = origL[i]
            }
        }

        val globalPaperL: Float
        val globalPaperR: Float
        val globalPaperG: Float
        val globalPaperB: Float
        val grainSigma: Float

        if (ringCount > 8) {
            val sortedL = ringLums.copyOf(ringCount).also { it.sort() }
            // 48th..82nd percentile captures the true mid-tone of surrounding paper (ignoring dark text and bright specular specks)
            val p48 = sortedL[(ringCount * 48) / 100]
            val p82 = sortedL[(ringCount * 82) / 100]
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumL = 0.0
            var sumL2 = 0.0
            var count = 0
            for (i in 0 until bTotal) {
                if (smoothAlpha[i] <= 0.04f && origL[i] in p48..p82) {
                    sumR += origR[i]
                    sumG += origG[i]
                    sumB += origB[i]
                    val lv = origL[i].toDouble()
                    sumL += lv
                    sumL2 += lv * lv
                    count++
                }
            }
            if (count > 0) {
                globalPaperR = (sumR / count).toFloat()
                globalPaperG = (sumG / count).toFloat()
                globalPaperB = (sumB / count).toFloat()
                globalPaperL = (sumL / count).toFloat()
                val meanL = sumL / count
                val variance = (sumL2 / count - meanL * meanL).coerceAtLeast(0.0)
                grainSigma = sqrt(variance).toFloat().coerceIn(0f, 3.0f)
            } else {
                globalPaperR = 246f; globalPaperG = 246f; globalPaperB = 244f; globalPaperL = 245f
                grainSigma = 0.6f
            }
        } else {
            globalPaperR = 246f; globalPaperG = 246f; globalPaperB = 244f; globalPaperL = 245f
            grainSigma = 0.6f
        }

        // 4. Content-Aware Coarse Grid Solver (matches local paper shade gradients on all sides of the stroke)
        val step = max(1, (radiusPx / 5.0f).roundToInt().coerceIn(1, 6))
        val gw = max(4, (bw + step - 1) / step)
        val gh = max(4, (bh + step - 1) / step)
        val gTotal = gw * gh

        val gR = FloatArray(gTotal)
        val gG = FloatArray(gTotal)
        val gB = FloatArray(gTotal)
        val gFixed = BooleanArray(gTotal)

        // Populate coarse boundary cells from unmasked paper pixels, rejecting dark ink crossings
        val inkRejectFloor = (globalPaperL * 0.78f).coerceAtMost(globalPaperL - 18f)
        val cellLums = FloatArray((step * 2 + 1) * (step * 2 + 1))

        for (gy in 0 until gh) {
            val py0 = (gy * step).coerceIn(0, bh - 1)
            val py1 = min(bh - 1, py0 + step - 1)
            for (gx in 0 until gw) {
                val px0 = (gx * step).coerceIn(0, bw - 1)
                val px1 = min(bw - 1, px0 + step - 1)

                var cCount = 0
                for (py in py0..py1) {
                    val rowOff = py * bw
                    for (px in px0..px1) {
                        val idx = rowOff + px
                        if (smoothAlpha[idx] < 0.06f && origL[idx] >= inkRejectFloor) {
                            if (cCount < cellLums.size) {
                                cellLums[cCount++] = origL[idx]
                            }
                        }
                    }
                }

                val gIdx = gy * gw + gx
                if (cCount >= max(1, (step * step) / 4)) {
                    // Use the median-to-65th percentile band of clean pixels in this cell so neither ink nor bright glare skews the shade
                    cellLums.sort(0, cCount)
                    val targetL = cellLums[(cCount * 62) / 100]
                    var sr = 0f
                    var sg = 0f
                    var sb = 0f
                    var wSum = 0f
                    for (py in py0..py1) {
                        val rowOff = py * bw
                        for (px in px0..px1) {
                            val idx = rowOff + px
                            if (smoothAlpha[idx] < 0.06f) {
                                val diff = abs(origL[idx] - targetL)
                                val w = if (diff <= 14f) 1f else if (origL[idx] >= inkRejectFloor) 0.15f else 0f
                                if (w > 0f) {
                                    sr += origR[idx] * w
                                    sg += origG[idx] * w
                                    sb += origB[idx] * w
                                    wSum += w
                                }
                            }
                        }
                    }
                    if (wSum > 0.1f) {
                        gR[gIdx] = sr / wSum
                        gG[gIdx] = sg / wSum
                        gB[gIdx] = sb / wSum
                        gFixed[gIdx] = true
                    }
                }
            }
        }

        // Clean any fixed boundary cell that was still pulled dark by an adjacent ink line using the MEDIAN
        // of its 5x5 fixed neighbors (never MAX, so we never create bright halos on off-white/shaded paper!)
        val cleanR = gR.copyOf()
        val cleanG = gG.copyOf()
        val cleanB = gB.copyOf()
        val nbrL = FloatArray(25)
        val nbrIdx = IntArray(25)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val gIdx = gy * gw + gx
                if (!gFixed[gIdx]) continue
                val curL = gR[gIdx] * 0.299f + gG[gIdx] * 0.587f + gB[gIdx] * 0.114f
                var nCount = 0
                for (dy in -2..2) {
                    val ny = (gy + dy).coerceIn(0, gh - 1)
                    for (dx in -2..2) {
                        val nx = (gx + dx).coerceIn(0, gw - 1)
                        val nI = ny * gw + nx
                        if (gFixed[nI]) {
                            nbrL[nCount] = gR[nI] * 0.299f + gG[nI] * 0.587f + gB[nI] * 0.114f
                            nbrIdx[nCount] = nI
                            nCount++
                        }
                    }
                }
                if (nCount >= 3) {
                    // Find 60th percentile neighbor by simple selection
                    for (i in 0 until nCount - 1) {
                        for (j in i + 1 until nCount) {
                            if (nbrL[j] < nbrL[i]) {
                                val tl = nbrL[i]; nbrL[i] = nbrL[j]; nbrL[j] = tl
                                val ti = nbrIdx[i]; nbrIdx[i] = nbrIdx[j]; nbrIdx[j] = ti
                            }
                        }
                    }
                    val medPos = (nCount * 60) / 100
                    val medL = nbrL[medPos]
                    if (medL - curL > 12f) {
                        val refIdx = nbrIdx[medPos]
                        cleanR[gIdx] = gR[refIdx]
                        cleanG[gIdx] = gG[refIdx]
                        cleanB[gIdx] = gB[refIdx]
                    }
                }
            }
        }
        System.arraycopy(cleanR, 0, gR, 0, gTotal)
        System.arraycopy(cleanG, 0, gG, 0, gTotal)
        System.arraycopy(cleanB, 0, gB, 0, gTotal)

        // Initialize unfixed (masked) cells via 4-directional inverse-distance boundary interpolation
        // so the stroke interior immediately takes the exact surrounding paper shade across the stroke width
        for (gy in 0 until gh) {
            val rowOff = gy * gw
            for (gx in 0 until gw) {
                val idx = rowOff + gx
                if (gFixed[idx]) continue

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f

                // Scan Left
                for (d in 1..gw) {
                    val nx = gx - d
                    if (nx < 0) break
                    val nIdx = rowOff + nx
                    if (gFixed[nIdx]) {
                        val w = 1f / (d + 0.5f)
                        sumR += gR[nIdx] * w; sumG += gG[nIdx] * w; sumB += gB[nIdx] * w; sumW += w
                        break
                    }
                }
                // Scan Right
                for (d in 1..gw) {
                    val nx = gx + d
                    if (nx >= gw) break
                    val nIdx = rowOff + nx
                    if (gFixed[nIdx]) {
                        val w = 1f / (d + 0.5f)
                        sumR += gR[nIdx] * w; sumG += gG[nIdx] * w; sumB += gB[nIdx] * w; sumW += w
                        break
                    }
                }
                // Scan Up
                for (d in 1..gh) {
                    val ny = gy - d
                    if (ny < 0) break
                    val nIdx = ny * gw + gx
                    if (gFixed[nIdx]) {
                        val w = 1f / (d + 0.5f)
                        sumR += gR[nIdx] * w; sumG += gG[nIdx] * w; sumB += gB[nIdx] * w; sumW += w
                        break
                    }
                }
                // Scan Down
                for (d in 1..gh) {
                    val ny = gy + d
                    if (ny >= gh) break
                    val nIdx = ny * gw + gx
                    if (gFixed[nIdx]) {
                        val w = 1f / (d + 0.5f)
                        sumR += gR[nIdx] * w; sumG += gG[nIdx] * w; sumB += gB[nIdx] * w; sumW += w
                        break
                    }
                }

                if (sumW > 0f) {
                    gR[idx] = sumR / sumW
                    gG[idx] = sumG / sumW
                    gB[idx] = sumB / sumW
                } else {
                    gR[idx] = globalPaperR
                    gG[idx] = globalPaperG
                    gB[idx] = globalPaperB
                }
            }
        }

        // Over-Relaxed Gauss-Seidel (SOR) harmonic relaxation across unfixed coarse cells for smooth shade flow
        val iterations = 64
        val omega = 1.42f
        for (iter in 0 until iterations) {
            for (gy in 0 until gh) {
                val rowOff = gy * gw
                val yUp = max(0, gy - 1) * gw
                val yDn = min(gh - 1, gy + 1) * gw
                for (gx in 0 until gw) {
                    val idx = rowOff + gx
                    if (gFixed[idx]) continue
                    val xL = max(0, gx - 1)
                    val xR = min(gw - 1, gx + 1)
                    val avgR = (gR[rowOff + xL] + gR[rowOff + xR] + gR[yUp + gx] + gR[yDn + gx]) * 0.25f
                    val avgG = (gG[rowOff + xL] + gG[rowOff + xR] + gG[yUp + gx] + gG[yDn + gx]) * 0.25f
                    val avgB = (gB[rowOff + xL] + gB[rowOff + xR] + gB[yUp + gx] + gB[yDn + gx]) * 0.25f
                    gR[idx] += (avgR - gR[idx]) * omega
                    gG[idx] += (avgG - gG[idx]) * omega
                    gB[idx] += (avgB - gB[idx]) * omega
                }
            }
        }

        // 5. Collect authentic paper micro-texture residuals from clean unmasked boundary paper pixels
        //    so the erased region inherits real paper fiber/grain like Photoshop Content-Aware Fill!
        val invW = (gw - 1).toFloat() / (bw - 1).coerceAtLeast(1)
        val invH = (gh - 1).toFloat() / (bh - 1).coerceAtLeast(1)

        val texResR = FloatArray(256)
        val texResG = FloatArray(256)
        val texResB = FloatArray(256)
        var texCount = 0

        val sampleStride = max(1, bTotal / 512)
        var sIdx = 0
        while (sIdx < bTotal && texCount < 256) {
            if (smoothAlpha[sIdx] <= 0.02f) {
                val py = sIdx / bw
                val px = sIdx % bw
                val gxF = px * invW
                val gyF = py * invH
                val gx0 = gxF.toInt().coerceIn(0, max(0, gw - 2))
                val gy0 = gyF.toInt().coerceIn(0, max(0, gh - 2))
                val gx1 = min(gw - 1, gx0 + 1)
                val gy1 = min(gh - 1, gy0 + 1)
                val fx = gxF - gx0
                val fy = gyF - gy0
                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy
                val rowG0 = gy0 * gw
                val rowG1 = gy1 * gw
                val baseR = gR[rowG0 + gx0] * w00 + gR[rowG0 + gx1] * w10 + gR[rowG1 + gx0] * w01 + gR[rowG1 + gx1] * w11
                val baseG = gG[rowG0 + gx0] * w00 + gG[rowG0 + gx1] * w10 + gG[rowG1 + gx0] * w01 + gG[rowG1 + gx1] * w11
                val baseB = gB[rowG0 + gx0] * w00 + gB[rowG0 + gx1] * w10 + gB[rowG1 + gx0] * w01 + gB[rowG1 + gx1] * w11
                val baseL = baseR * 0.299f + baseG * 0.587f + baseB * 0.114f
                if (abs(origL[sIdx] - baseL) <= 9f) {
                    texResR[texCount] = (origR[sIdx] - baseR).coerceIn(-6f, 6f)
                    texResG[texCount] = (origG[sIdx] - baseG).coerceIn(-6f, 6f)
                    texResB[texCount] = (origB[sIdx] - baseB).coerceIn(-6f, 6f)
                    texCount++
                }
            }
            sIdx += sampleStride
        }

        // 6. Bilinearly upsample healed paper surface and blend with feathered stroke mask + authentic paper texture
        for (py in 0 until bh) {
            val rowOff = py * bw
            val gyF = py * invH
            val gy0 = gyF.toInt().coerceIn(0, max(0, gh - 2))
            val gy1 = min(gh - 1, gy0 + 1)
            val fy = gyF - gy0
            val rowG0 = gy0 * gw
            val rowG1 = gy1 * gw

            for (px in 0 until bw) {
                val idx = rowOff + px
                val alpha = smoothAlpha[idx]
                if (alpha <= 0.005f) continue

                val gxF = px * invW
                val gx0 = gxF.toInt().coerceIn(0, max(0, gw - 2))
                val gx1 = min(gw - 1, gx0 + 1)
                val fx = gxF - gx0

                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy

                val healR = gR[rowG0 + gx0] * w00 + gR[rowG0 + gx1] * w10 + gR[rowG1 + gx0] * w01 + gR[rowG1 + gx1] * w11
                val healG = gG[rowG0 + gx0] * w00 + gG[rowG0 + gx1] * w10 + gG[rowG1 + gx0] * w01 + gG[rowG1 + gx1] * w11
                val healB = gB[rowG0 + gx0] * w00 + gB[rowG0 + gx1] * w10 + gB[rowG1 + gx0] * w01 + gB[rowG1 + gx1] * w11

                val dR: Float
                val dG: Float
                val dB: Float
                if (texCount >= 16) {
                    val h1 = (((bx0 + px) * 17 + (by0 + py) * 31) and 0x7FFFFFFF) % texCount
                    val h2 = (((bx0 + px) * 73856093 xor (by0 + py) * 19349663) and 0x7FFFFFFF) % texCount
                    dR = (texResR[h1] + texResR[h2]) * 0.5f
                    dG = (texResG[h1] + texResG[h2]) * 0.5f
                    dB = (texResB[h1] + texResB[h2]) * 0.5f
                } else {
                    val hash = (((bx0 + px) * 73856093) xor ((by0 + py) * 19349663)) and 0xFFFF
                    val noise = ((hash / 32767.5f) - 1f) * grainSigma * 0.7f
                    dR = noise
                    dG = noise
                    dB = noise
                }

                val targetR = (healR + dR).coerceIn(0f, 255f)
                val targetG = (healG + dG).coerceIn(0f, 255f)
                val targetB = (healB + dB).coerceIn(0f, 255f)

                val outR = (origR[idx] * (1f - alpha) + targetR * alpha).roundToInt().coerceIn(0, 255)
                val outG = (origG[idx] * (1f - alpha) + targetG * alpha).roundToInt().coerceIn(0, 255)
                val outB = (origB[idx] * (1f - alpha) + targetB * alpha).roundToInt().coerceIn(0, 255)

                roiPixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }

        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fullPixels = IntArray(w * h)
        source.getPixels(fullPixels, 0, w, 0, 0, w, h)
        outBitmap.setPixels(fullPixels, 0, w, 0, 0, w, h)
        outBitmap.setPixels(roiPixels, 0, bw, bx0, by0, bw, bh)
        return outBitmap
    }
}
