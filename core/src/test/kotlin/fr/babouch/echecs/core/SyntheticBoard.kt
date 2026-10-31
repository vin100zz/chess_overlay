package fr.babouch.echecs.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/** Dessine une fausse capture d'écran contenant un échiquier, pour les tests. */
object SyntheticBoard {
    data class Layout(
        val width: Int = 1080,
        val height: Int = 2340,
        val boardX: Int = 0,
        val boardY: Int = 620,
        val boardSize: Int = 1080,
    )

    /** [board] indexé a1..h8. [highlight] : cases (a1..h8) surlignées comme un dernier coup. */
    fun render(board: CharArray, whiteAtBottom: Boolean, layout: Layout = Layout(), highlight: Set<Int> = emptySet()): RgbImage {
        val img = BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x30, 0x2e, 0x2b)
        g.fillRect(0, 0, layout.width, layout.height)
        // Barres « joueurs » au-dessus et en dessous
        g.color = Color(0x45, 0x43, 0x40)
        g.fillRect(20, layout.boardY - 150, layout.width - 40, 110)
        g.fillRect(20, layout.boardY + layout.boardSize + 40, layout.width - 40, 110)
        val sq = layout.boardSize / 8.0
        for (row in 0 until 8) for (col in 0 until 8) {
            val square = Orientation.toSquare(row, col, whiteAtBottom)
            val dark = (row + col) % 2 == 1
            var c = if (dark) Color(0x76, 0x96, 0x56) else Color(0xee, 0xee, 0xd2)
            if (square in highlight) c = if (dark) Color(0xba, 0xca, 0x2b) else Color(0xf6, 0xf6, 0x69)
            g.color = c
            val x = (layout.boardX + col * sq).toInt()
            val y = (layout.boardY + row * sq).toInt()
            g.fillRect(x, y, (layout.boardX + (col + 1) * sq).toInt() - x, (layout.boardY + (row + 1) * sq).toInt() - y)
            val p = board[square]
            if (p != '.') drawPiece(g, p, layout.boardX + col * sq, layout.boardY + row * sq, sq)
        }
        g.dispose()
        val px = IntArray(layout.width * layout.height)
        img.getRGB(0, 0, layout.width, layout.height, px, 0, layout.width)
        return RgbImage(layout.width, layout.height, px)
    }

    private fun drawPiece(g: java.awt.Graphics2D, p: Char, x: Double, y: Double, sq: Double) {
        val white = p.isUpperCase()
        val fill = if (white) Color(0xf8, 0xf8, 0xf8) else Color(0x20, 0x20, 0x20)
        val outline = if (white) Color(0x10, 0x10, 0x10) else Color(0x10, 0x10, 0x10)
        fun X(f: Double) = (x + f * sq).toInt()
        fun Y(f: Double) = (y + f * sq).toInt()
        val shapes = mutableListOf<java.awt.Shape>()
        // Socle commun
        shapes += Polygon(intArrayOf(X(0.25), X(0.75), X(0.7), X(0.3)), intArrayOf(Y(0.88), Y(0.88), Y(0.8), Y(0.8)), 4)
        when (p.lowercaseChar()) {
            'p' -> {
                shapes += Ellipse2D.Double(x + 0.38 * sq, y + 0.3 * sq, 0.24 * sq, 0.24 * sq)
                shapes += Polygon(intArrayOf(X(0.35), X(0.65), X(0.58), X(0.42)), intArrayOf(Y(0.8), Y(0.8), Y(0.52), Y(0.52)), 4)
            }
            'r' -> shapes += Polygon(
                intArrayOf(X(0.3), X(0.3), X(0.38), X(0.38), X(0.46), X(0.46), X(0.54), X(0.54), X(0.62), X(0.62), X(0.7), X(0.7)),
                intArrayOf(Y(0.8), Y(0.2), Y(0.2), Y(0.28), Y(0.28), Y(0.2), Y(0.2), Y(0.28), Y(0.28), Y(0.2), Y(0.2), Y(0.8)), 12,
            )
            'n' -> shapes += Polygon(
                intArrayOf(X(0.3), X(0.32), X(0.22), X(0.28), X(0.5), X(0.62), X(0.72), X(0.7)),
                intArrayOf(Y(0.8), Y(0.5), Y(0.42), Y(0.32), Y(0.15), Y(0.2), Y(0.45), Y(0.8)), 8,
            )
            'b' -> {
                shapes += Ellipse2D.Double(x + 0.34 * sq, y + 0.25 * sq, 0.32 * sq, 0.42 * sq)
                shapes += Ellipse2D.Double(x + 0.46 * sq, y + 0.13 * sq, 0.08 * sq, 0.08 * sq)
                shapes += Polygon(intArrayOf(X(0.38), X(0.62), X(0.56), X(0.44)), intArrayOf(Y(0.8), Y(0.8), Y(0.6), Y(0.6)), 4)
            }
            'q' -> shapes += Polygon(
                intArrayOf(X(0.28), X(0.18), X(0.38), X(0.5), X(0.62), X(0.82), X(0.72)),
                intArrayOf(Y(0.8), Y(0.25), Y(0.45), Y(0.15), Y(0.45), Y(0.25), Y(0.8)), 7,
            )
            'k' -> {
                shapes += Polygon(intArrayOf(X(0.28), X(0.24), X(0.76), X(0.72)), intArrayOf(Y(0.8), Y(0.4), Y(0.4), Y(0.8)), 4)
                shapes += Polygon(
                    intArrayOf(X(0.46), X(0.46), X(0.38), X(0.38), X(0.46), X(0.46), X(0.54), X(0.54), X(0.62), X(0.62), X(0.54), X(0.54)),
                    intArrayOf(Y(0.4), Y(0.26), Y(0.26), Y(0.2), Y(0.2), Y(0.1), Y(0.1), Y(0.2), Y(0.2), Y(0.26), Y(0.26), Y(0.4)), 12,
                )
            }
        }
        g.stroke = BasicStroke((sq * 0.025).toFloat())
        for (s in shapes) {
            g.color = fill
            g.fill(s)
            g.color = outline
            g.draw(s)
        }
    }
}
