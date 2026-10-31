package fr.babouch.echecs.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Position de l'échiquier à l'écran, en pixels. (x, y) = coin haut-gauche. */
data class BoardRect(val x: Float, val y: Float, val size: Float) {
    val square: Float get() = size / 8f
    fun cellX(col: Int, f: Float = 0.5f): Float = x + (col + f) * square
    fun cellY(row: Int, f: Float = 0.5f): Float = y + (row + f) * square
}

/**
 * Recherche d'un échiquier 8x8 dans une capture d'écran.
 *
 * Principe : on cherche le carré (x, y, taille) pour lequel les couleurs prises près des coins
 * des 64 cases (là où il n'y a jamais de pièce) se séparent le mieux en deux groupes
 * « cases claires » / « cases foncées » disposés en damier. Recherche grossière sur une image
 * réduite, puis affinage en pleine résolution, puis calage précis sur les bords des cases.
 */
object BoardDetector {
    private const val SMALL_WIDTH = 120
    private const val MIN_SCORE = 3f
    private const val MIN_CONTRAST = 25f

    // Points d'échantillonnage dans une case (fractions x, y), loin du centre où sont les pièces.
    private val COARSE_POINTS = floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f)
    private val FINE_POINTS = floatArrayOf(0.12f, 0.12f, 0.88f, 0.12f, 0.12f, 0.88f, 0.88f, 0.88f)

    fun detect(img: RgbImage): BoardRect? {
        val small = Small.of(img, SMALL_WIDTH)
        val coarse = coarseSearch(small) ?: return null
        val s = small.scale
        val mid = fisherRefine(img, BoardRect(coarse.x * s, coarse.y * s, coarse.size * s), s)
        // Le calage sur les bords est répété tant qu'il bouge (la recherche se fait dans une fenêtre limitée).
        var fine = mid
        for (iter in 0 until 4) {
            val next = edgeRefine(img, fine)
            val moved = abs(next.x - fine.x) + abs(next.y - fine.y) + abs(next.size - fine.size)
            fine = next
            if (moved < 1f) break
        }
        val stats = FloatArray(1)
        val score = fisher(FullSampler(img), fine.x, fine.y, fine.square, FINE_POINTS, stats)
        if (score < MIN_SCORE || stats[0] < MIN_CONTRAST) return null
        return fine
    }

    /** Score (et contraste) d'un rectangle donné : utile pour vérifier une position mémorisée. */
    fun score(img: RgbImage, rect: BoardRect): Float =
        fisher(FullSampler(img), rect.x, rect.y, rect.square, FINE_POINTS, null)

    // ---------------------------------------------------------------------------------------

    private interface Sampler {
        val w: Int
        val h: Int
        fun sample(x: Float, y: Float, out: FloatArray)
    }

    private class Small(
        override val w: Int,
        override val h: Int,
        val rgb: FloatArray,
        val scale: Float,
    ) : Sampler {
        override fun sample(x: Float, y: Float, out: FloatArray) {
            val ix = x.toInt().coerceIn(0, w - 1)
            val iy = y.toInt().coerceIn(0, h - 1)
            val i = (iy * w + ix) * 3
            out[0] = rgb[i]
            out[1] = rgb[i + 1]
            out[2] = rgb[i + 2]
        }

        companion object {
            fun of(img: RgbImage, targetW: Int): Small {
                val scale = max(1f, img.width.toFloat() / targetW)
                val w = max(1, (img.width / scale).toInt())
                val h = max(1, (img.height / scale).toInt())
                val rgb = FloatArray(w * h * 3)
                val sub = 3
                for (sy in 0 until h) {
                    for (sx in 0 until w) {
                        var r = 0
                        var g = 0
                        var b = 0
                        for (j in 0 until sub) {
                            for (i in 0 until sub) {
                                val p = img.argb(
                                    ((sx + (i + 0.5f) / sub) * scale).toInt(),
                                    ((sy + (j + 0.5f) / sub) * scale).toInt(),
                                )
                                r += (p shr 16) and 0xFF
                                g += (p shr 8) and 0xFF
                                b += p and 0xFF
                            }
                        }
                        val k = (sy * w + sx) * 3
                        val n = (sub * sub).toFloat()
                        rgb[k] = r / n
                        rgb[k + 1] = g / n
                        rgb[k + 2] = b / n
                    }
                }
                return Small(w, h, rgb, scale)
            }
        }
    }

    private class FullSampler(val img: RgbImage) : Sampler {
        override val w: Int get() = img.width
        override val h: Int get() = img.height
        override fun sample(x: Float, y: Float, out: FloatArray) = img.average(x, y, 1, out)
    }

    /**
     * Critère de Fisher : écart entre la couleur moyenne des cases claires et celle des cases
     * foncées, rapporté à la dispersion à l'intérieur de chaque groupe.
     * La case en bas à gauche doit être foncée (vrai quel que soit le sens de l'échiquier).
     */
    private fun fisher(
        s: Sampler, x0: Float, y0: Float, sq: Float, pts: FloatArray, contrastOut: FloatArray?,
    ): Float {
        val c = FloatArray(3)
        var nL = 0
        var nD = 0
        val sumL = FloatArray(3)
        val sumD = FloatArray(3)
        var sqL = 0f
        var sqD = 0f
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val dark = ((row + col) and 1) == 1
                var k = 0
                while (k < pts.size) {
                    s.sample(x0 + (col + pts[k]) * sq, y0 + (row + pts[k + 1]) * sq, c)
                    val n2 = c[0] * c[0] + c[1] * c[1] + c[2] * c[2]
                    if (dark) {
                        nD++; sumD[0] += c[0]; sumD[1] += c[1]; sumD[2] += c[2]; sqD += n2
                    } else {
                        nL++; sumL[0] += c[0]; sumL[1] += c[1]; sumL[2] += c[2]; sqL += n2
                    }
                    k += 2
                }
            }
        }
        for (i in 0..2) {
            sumL[i] /= nL
            sumD[i] /= nD
        }
        val varL = max(0f, sqL / nL - (sumL[0] * sumL[0] + sumL[1] * sumL[1] + sumL[2] * sumL[2]))
        val varD = max(0f, sqD / nD - (sumD[0] * sumD[0] + sumD[1] * sumD[1] + sumD[2] * sumD[2]))
        val diff2 = dist2(sumL, sumD)
        var score = diff2 / (varL + varD + 25f)
        if (luminance(sumD[0], sumD[1], sumD[2]) > luminance(sumL[0], sumL[1], sumL[2])) score *= 0.5f
        contrastOut?.set(0, sqrt(diff2))
        return score
    }

    private fun coarseSearch(small: Small): BoardRect? {
        val maxS = min(small.w, small.h)
        val minS = (maxS * 0.35f).toInt()
        if (minS < 16) return null
        var best = -1f
        var bx = 0
        var by = 0
        var bs = 0
        for (s in maxS downTo minS) {
            val sq = s / 8f
            var y = 0
            while (y <= small.h - s) {
                var x = 0
                while (x <= small.w - s) {
                    val sc = fisher(small, x.toFloat(), y.toFloat(), sq, COARSE_POINTS, null)
                    if (sc > best) {
                        best = sc; bx = x; by = y; bs = s
                    }
                    x += 2
                }
                y += 2
            }
        }
        if (best < MIN_SCORE / 2) return null
        return BoardRect(bx.toFloat(), by.toFloat(), bs.toFloat())
    }

    private fun fisherRefine(img: RgbImage, start: BoardRect, scale: Float): BoardRect {
        val sampler = FullSampler(img)
        val range = (2.5f * scale).roundToInt().coerceAtLeast(2)
        val step = (scale / 3f).roundToInt().coerceAtLeast(1)
        var best = -1f
        var res = start
        var ds = -range
        while (ds <= range) {
            val size = start.size + ds
            if (size >= 32f) {
                var dy = -range
                while (dy <= range) {
                    var dx = -range
                    while (dx <= range) {
                        val x = start.x + dx
                        val y = start.y + dy
                        val sc = fisher(sampler, x, y, size / 8f, FINE_POINTS, null)
                        if (sc > best) {
                            best = sc
                            res = BoardRect(x, y, size)
                        }
                        dx += step
                    }
                    dy += step
                }
            }
            ds += step
        }
        return res
    }

    /**
     * Netteté des bords des cases : différence de couleur de part et d'autre de chaque ligne de la
     * grille (bords extérieurs compris), maximale quand la grille tombe pile sur les bords.
     */
    private fun edgeScore(img: RgbImage, x0: Float, y0: Float, size: Float, a: FloatArray, b: FloatArray): Float {
        val sq = size / 8f
        val d = 1.5f
        var total = 0f
        for (k in 0..8) {
            val line = k * sq
            for (cell in 0 until 8) {
                for (f in EDGE_FRACTIONS) {
                    val along = (cell + f) * sq
                    // Ligne verticale
                    img.average(x0 + line - d, y0 + along, 0, a)
                    img.average(x0 + line + d, y0 + along, 0, b)
                    total += sqrt(dist2(a, b))
                    // Ligne horizontale
                    img.average(x0 + along, y0 + line - d, 0, a)
                    img.average(x0 + along, y0 + line + d, 0, b)
                    total += sqrt(dist2(a, b))
                }
            }
        }
        return total
    }

    private val EDGE_FRACTIONS = floatArrayOf(0.15f, 0.85f)

    private fun edgeRefine(img: RgbImage, start: BoardRect): BoardRect {
        val range = max(4, (start.square * 0.1f).roundToInt())
        val a = FloatArray(3)
        val b = FloatArray(3)
        val n = 2 * range + 1
        val scores = FloatArray(n * n * n)
        var best = -1f
        for (ds in -range..range) for (dy in -range..range) for (dx in -range..range) {
            val sc = edgeScore(img, start.x + dx, start.y + dy, start.size + ds, a, b)
            scores[((ds + range) * n + (dy + range)) * n + (dx + range)] = sc
            if (sc > best) best = sc
        }
        // Moyenne des meilleures positions (le maximum forme souvent un petit plateau).
        var sx = 0f
        var sy = 0f
        var ss = 0f
        var cnt = 0
        for (ds in -range..range) for (dy in -range..range) for (dx in -range..range) {
            if (scores[((ds + range) * n + (dy + range)) * n + (dx + range)] >= best * 0.985f) {
                sx += dx; sy += dy; ss += ds; cnt++
            }
        }
        return BoardRect(start.x + sx / cnt, start.y + sy / cnt, start.size + ss / cnt)
    }
}
