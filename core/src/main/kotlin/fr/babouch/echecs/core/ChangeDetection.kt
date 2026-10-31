package fr.babouch.echecs.core

import kotlin.math.abs

/**
 * Surveillance légère de l'échiquier à l'écran, pour le suivi automatique : on relève quelques
 * points par case (sans retirer l'overlay), et on compare avec un relevé de référence. Les flèches
 * dessinées par l'appli ne bougent pas entre deux relevés : seul un changement sur l'échiquier
 * (coup joué, case surlignée…) modifie les cases.
 */
object ScreenDiff {
    /** Nombre de points relevés par côté de case. */
    const val GRID = 6
    const val POINTS = GRID * GRID

    /**
     * Écart (moyenne des 3 canaux, sur 255) à partir duquel un point a changé. Assez bas pour voir
     * une pièce blanche arriver sur une case claire (seul son dessin intérieur contraste un peu).
     */
    const val POINT_THRESHOLD = 12

    /** Nombre de points modifiés à partir duquel une case a changé. */
    const val MIN_POINTS = 3

    /**
     * Relevé des 64 cases (ordre de l'écran, ligne par ligne). [pixel] renvoie la couleur ARGB d'un
     * pixel de l'écran, ses coordonnées étant toujours dans l'image.
     */
    inline fun sample(rect: BoardRect, width: Int, height: Int, pixel: (Int, Int) -> Int): IntArray {
        val out = IntArray(64 * POINTS)
        val sq = rect.square
        var k = 0
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                for (j in 0 until GRID) {
                    val y = (rect.y + (row + (j + 0.5f) / GRID) * sq).toInt().coerceIn(0, height - 1)
                    for (i in 0 until GRID) {
                        val x = (rect.x + (col + (i + 0.5f) / GRID) * sq).toInt().coerceIn(0, width - 1)
                        out[k++] = pixel(x, y)
                    }
                }
            }
        }
        return out
    }

    fun sample(img: RgbImage, rect: BoardRect): IntArray =
        sample(rect, img.width, img.height) { x, y -> img.pixels[y * img.width + x] }

    /** Nombre de cases qui diffèrent entre deux relevés. */
    fun changedSquares(a: IntArray, b: IntArray): Int {
        if (a.size != b.size) return 64
        var changed = 0
        for (s in 0 until 64) {
            var points = 0
            for (p in s * POINTS until (s + 1) * POINTS) {
                val u = a[p]
                val v = b[p]
                val d = abs(((u shr 16) and 0xFF) - ((v shr 16) and 0xFF)) +
                    abs(((u shr 8) and 0xFF) - ((v shr 8) and 0xFF)) +
                    abs((u and 0xFF) - (v and 0xFF))
                if (d >= 3 * POINT_THRESHOLD) points++
            }
            if (points >= MIN_POINTS) changed++
        }
        return changed
    }
}

/** Qui a joué entre deux positions reconnues. */
enum class Mover {
    /** Rien n'a changé. */
    NONE,
    WHITE,
    BLACK,

    /** Un coup de chaque camp (deux demi-coups). */
    BOTH,

    /** Changement inexplicable par un ou deux coups (nouvelle partie, autre position, erreur de lecture). */
    UNKNOWN,
}

object MoveDiff {
    /**
     * Déduit le ou les camps qui ont joué entre [before] et [after] (échiquiers indexés a1..h8).
     * Tout coup fait « arriver » une pièce du camp qui joue sur une case (case d'arrivée, roque :
     * roi et tour, promotion : nouvelle pièce) ; les pièces prises, elles, ne font que disparaître.
     */
    fun mover(before: CharArray, after: CharArray): Mover {
        var changed = 0
        var whiteArrivals = 0
        var blackArrivals = 0
        var whiteLeft = 0
        var blackLeft = 0
        for (i in 0 until 64) {
            val b = before[i]
            val a = after[i]
            if (a == b) continue
            changed++
            if (a.isUpperCase()) whiteArrivals++ else if (a.isLowerCase()) blackArrivals++
            if (b.isUpperCase()) whiteLeft++ else if (b.isLowerCase()) blackLeft++
        }
        if (changed == 0) return Mover.NONE
        // Un camp fait au plus 2 arrivées par coup (roque). Deux coups changent au plus 8 cases.
        if (changed > 8 || whiteArrivals > 2 || blackArrivals > 2) return Mover.UNKNOWN
        return when {
            whiteArrivals > 0 && blackArrivals > 0 -> Mover.BOTH
            // Un coup des blancs : seules des pièces blanches partent (plus, au plus, une pièce noire prise).
            whiteArrivals > 0 && whiteLeft >= whiteArrivals && blackLeft <= 1 -> Mover.WHITE
            blackArrivals > 0 && blackLeft >= blackArrivals && whiteLeft <= 1 -> Mover.BLACK
            else -> Mover.UNKNOWN
        }
    }
}
