package fr.babouch.echecs.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Une case de référence apprise lors de la calibration : son contenu ('.' = vide) et sa signature. */
class Template(val label: Char, val features: FloatArray)

class CalibrationException(message: String) : Exception(message)

/** Résultat de la calibration : les modèles appris et le sens de l'échiquier au moment de la calibration. */
class Calibration(val templates: List<Template>, val whiteAtBottom: Boolean)

/**
 * Reconnaissance des pièces par comparaison avec des modèles appris sur la position initiale.
 *
 * Pour chaque case on calcule une petite « signature » de N x N cellules : pour chaque cellule,
 * à quel point elle diffère de la couleur de fond de la case (= forme de la pièce), et sa
 * luminosité (= couleur de la pièce). Le fond est estimé sur les coins de la case elle-même, ce
 * qui neutralise les cases surlignées (dernier coup, sélection). On classe ensuite chaque case
 * par plus proche voisin parmi les modèles.
 */
object PieceRecognizer {
    const val N = 24
    const val FEATURE_SIZE = 2 * N * N
    private const val INSET = 0.08f
    private const val CORNER = 0.05f

    private const val START = "RNBQKBNR" + "PPPPPPPP" + "........" + "........" +
        "........" + "........" + "pppppppp" + "rnbqkbnr"

    fun features(img: RgbImage, rect: BoardRect, row: Int, col: Int): FloatArray {
        val sq = rect.square
        val left = rect.x + col * sq
        val top = rect.y + row * sq
        val bg = background(img, left, top, sq)
        val radius = if (sq / N >= 4f) 1 else 0
        val f = FloatArray(FEATURE_SIZE)
        val cell = sq * (1f - 2f * INSET) / N
        val x0 = left + sq * INSET
        val y0 = top + sq * INSET
        val c = FloatArray(3)
        for (j in 0 until N) {
            for (i in 0 until N) {
                img.average(x0 + (i + 0.5f) * cell, y0 + (j + 0.5f) * cell, radius, c)
                val d = sqrt(dist2(c, bg))
                val w = ((d - 18f) / 30f).coerceIn(0f, 1f)
                val lum = luminance(c[0], c[1], c[2])
                f[j * N + i] = w
                f[N * N + j * N + i] = w * (lum - 128f) / 128f
            }
        }
        return f
    }

    /** Couleur de fond d'une case : le plus « central » des 4 coins (résiste à un chiffre de coordonnée dans un coin). */
    private fun background(img: RgbImage, left: Float, top: Float, sq: Float): FloatArray {
        val pts = arrayOf(
            floatArrayOf(CORNER, CORNER), floatArrayOf(1 - CORNER, CORNER),
            floatArrayOf(CORNER, 1 - CORNER), floatArrayOf(1 - CORNER, 1 - CORNER),
        )
        val cols = Array(4) { k ->
            FloatArray(3).also { img.average(left + pts[k][0] * sq, top + pts[k][1] * sq, 1, it) }
        }
        var best = 0
        var bestSum = Float.MAX_VALUE
        for (i in 0 until 4) {
            var s = 0f
            for (j in 0 until 4) s += sqrt(dist2(cols[i], cols[j]))
            if (s < bestSum) {
                bestSum = s; best = i
            }
        }
        return cols[best]
    }

    fun coverage(f: FloatArray): Float {
        var s = 0f
        for (i in 0 until N * N) s += f[i]
        return s / (N * N)
    }

    /** Luminosité moyenne (-1..1) des pixels de la pièce. */
    private fun pieceLum(f: FloatArray): Float {
        var w = 0f
        var l = 0f
        for (i in 0 until N * N) {
            w += f[i]; l += f[N * N + i]
        }
        return if (w > 0f) l / w else 0f
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return s
    }

    /** Apprend les modèles à partir d'une capture montrant la position initiale (dans un sens ou dans l'autre). */
    fun calibrate(img: RgbImage, rect: BoardRect): Calibration {
        val feats = Array(64) { features(img, rect, it / 8, it % 8) }
        val cover = FloatArray(64) { coverage(feats[it]) }
        var minPiece = Float.MAX_VALUE
        var maxEmpty = 0f
        for (i in 0 until 64) {
            val row = i / 8
            if (row in 2..5) maxEmpty = maxOf(maxEmpty, cover[i]) else minPiece = minOf(minPiece, cover[i])
        }
        if (minPiece < 0.05f || minPiece < maxEmpty * 1.5f + 0.01f) {
            throw CalibrationException(
                "Ce n'est pas la position initiale : affichez une partie qui commence, puis réessayez.",
            )
        }
        var top = 0f
        var bottom = 0f
        for (i in 0 until 16) top += pieceLum(feats[i])
        for (i in 48 until 64) bottom += pieceLum(feats[i])
        top /= 16; bottom /= 16
        if (abs(bottom - top) < 0.1f) {
            throw CalibrationException("Impossible de distinguer les pièces blanches des noires.")
        }
        val whiteAtBottom = bottom > top
        val templates = (0 until 64).map { i ->
            Template(startLabel(i / 8, i % 8, whiteAtBottom), feats[i])
        }
        return Calibration(templates, whiteAtBottom)
    }

    private fun startLabel(row: Int, col: Int, whiteAtBottom: Boolean): Char =
        START[Orientation.toSquare(row, col, whiteAtBottom)]

    /** Contenu des 64 cases dans l'ordre de l'écran (ligne du haut d'abord). */
    fun recognize(img: RgbImage, rect: BoardRect, templates: List<Template>): CharArray {
        require(templates.isNotEmpty())
        return CharArray(64) { i ->
            val f = features(img, rect, i / 8, i % 8)
            var best = Float.MAX_VALUE
            var label = '.'
            for (t in templates) {
                val d = distance(f, t.features)
                if (d < best) {
                    best = d; label = t.label
                }
            }
            label
        }
    }

    /**
     * Devine le sens de l'échiquier d'après la position des pions et des rois.
     * Retourne null si on ne peut pas trancher.
     */
    fun guessWhiteAtBottom(screen: CharArray): Boolean? {
        var score = 0f
        for (i in 0 until 64) {
            val r = i / 8 - 3.5f
            when (screen[i]) {
                'P' -> score += r
                'p' -> score -= r
                'K' -> score += 2 * r
                'k' -> score -= 2 * r
            }
        }
        return when {
            score > 0f -> true
            score < 0f -> false
            else -> null
        }
    }
}

/** Correspondance entre cases de l'écran (ligne, colonne) et cases de l'échiquier (0 = a1, 63 = h8). */
object Orientation {
    fun toSquare(row: Int, col: Int, whiteAtBottom: Boolean): Int =
        if (whiteAtBottom) (7 - row) * 8 + col else row * 8 + (7 - col)

    fun rowOf(square: Int, whiteAtBottom: Boolean): Int =
        if (whiteAtBottom) 7 - square / 8 else square / 8

    fun colOf(square: Int, whiteAtBottom: Boolean): Int =
        if (whiteAtBottom) square % 8 else 7 - square % 8

    /** Convertit le contenu de l'écran en échiquier indexé a1..h8. */
    fun toBoard(screen: CharArray, whiteAtBottom: Boolean): CharArray {
        val board = CharArray(64) { '.' }
        for (i in 0 until 64) board[toSquare(i / 8, i % 8, whiteAtBottom)] = screen[i]
        return board
    }
}
