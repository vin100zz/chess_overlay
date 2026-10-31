package fr.babouch.echecs.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private fun boardFromFen(fen: String): CharArray {
        val b = CharArray(64) { '.' }
        val rows = fen.split(" ")[0].split("/")
        for ((i, row) in rows.withIndex()) {
            val rank = 7 - i
            var file = 0
            for (c in row) {
                if (c.isDigit()) file += c - '0' else b[rank * 8 + file++] = c
            }
        }
        return b
    }

    private val start = boardFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
    private val middle = boardFromFen("r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R")

    @Test
    fun fenEtRoques() {
        val p = Position(start, true)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", p.toFen())
        assertNull(p.validate())
    }

    @Test
    fun echecDuCampSansTrait() {
        // Le roi noir est en échec par la dame blanche, et c'est aux blancs de jouer : impossible.
        val b = boardFromFen("4k3/8/8/8/8/8/4Q3/4K3")
        assertNotNull(Position(b, true).validate())
        assertNull(Position(b, false).validate())
    }

    @Test
    fun notationFrancaise() {
        val p = Position(middle, true)
        assertEquals("Fc4xf7", p.describe("c4f7"))
        assertEquals("O-O", p.describe("e1g1"))
        assertEquals("Cf3xe5", p.describe("f3e5"))
        assertEquals("a2-a4", p.describe("a2a4"))
    }

    @Test
    fun analyseUci() {
        val l = UciParser.parseInfo(
            "info depth 18 seldepth 25 multipv 2 score cp -37 upperbound nodes 1 nps 2 pv e7e5 g1f3 b8c6",
        )
        assertNotNull(l)
        assertEquals(2, l.multipv)
        assertEquals(18, l.depth)
        assertEquals(-37, l.cp)
        assertEquals(listOf("e7e5", "g1f3", "b8c6"), l.pv)
        assertEquals("+0.37", l.scoreText(whiteToMove = false))
        val m = UciParser.parseInfo("info depth 5 score mate -2 pv h7h8")!!
        assertEquals("#2", m.scoreText(whiteToMove = false))
        assertNull(UciParser.parseInfo("info string NNUE evaluation"))
    }

    private fun checkRecognition(whiteAtBottom: Boolean, layout: SyntheticBoard.Layout) {
        val calib = SyntheticBoard.render(start, whiteAtBottom, layout)
        val rect = BoardDetector.detect(calib)
        assertNotNull(rect, "échiquier non trouvé")
        val tol = layout.boardSize * 0.01f
        assertTrue(abs(rect.x - layout.boardX) < tol, "x=${rect.x}")
        assertTrue(abs(rect.y - layout.boardY) < tol, "y=${rect.y}")
        assertTrue(abs(rect.size - layout.boardSize) < tol, "taille=${rect.size}")

        val c = PieceRecognizer.calibrate(calib, rect)
        assertEquals(whiteAtBottom, c.whiteAtBottom)

        val shot = SyntheticBoard.render(middle, whiteAtBottom, layout, highlight = setOf(21, 6))
        val rect2 = BoardDetector.detect(shot)!!
        val screen = PieceRecognizer.recognize(shot, rect2, c.templates)
        assertEquals(whiteAtBottom, PieceRecognizer.guessWhiteAtBottom(screen))
        val board = Orientation.toBoard(screen, whiteAtBottom)
        assertEquals(String(middle), String(board))
    }

    @Test
    fun reconnaissanceBlancsEnBas() = checkRecognition(true, SyntheticBoard.Layout())

    @Test
    fun reconnaissanceNoirsEnBas() =
        checkRecognition(false, SyntheticBoard.Layout(width = 720, height = 1600, boardX = 12, boardY = 401, boardSize = 696))
}
