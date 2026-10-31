package fr.babouch.echecs.core

/**
 * Position d'échecs minimale : 64 cases indexées de a1 (0) à h8 (63).
 * Pièces en notation FEN : majuscules = blancs, minuscules = noirs, '.' = case vide.
 */
class Position(board: CharArray, val whiteToMove: Boolean) {
    val board: CharArray = board.copyOf()

    init {
        require(board.size == 64)
    }

    fun at(file: Int, rank: Int): Char = board[rank * 8 + file]

    /** Droits de roque déduits : roi et tour encore sur leurs cases d'origine. */
    fun castling(): String {
        val sb = StringBuilder()
        if (at(4, 0) == 'K') {
            if (at(7, 0) == 'R') sb.append('K')
            if (at(0, 0) == 'R') sb.append('Q')
        }
        if (at(4, 7) == 'k') {
            if (at(7, 7) == 'r') sb.append('k')
            if (at(0, 7) == 'r') sb.append('q')
        }
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0 until 8) {
                val c = at(file, rank)
                if (c == '.') {
                    empty++
                } else {
                    if (empty > 0) sb.append(empty)
                    empty = 0
                    sb.append(c)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        sb.append(castling())
        sb.append(" - 0 1")
        return sb.toString()
    }

    fun withSideToMove(white: Boolean) = Position(board, white)

    fun kingSquare(white: Boolean): Int = board.indexOf(if (white) 'K' else 'k')

    /** La case [sq] est-elle attaquée par le camp [byWhite] ? */
    fun isAttacked(sq: Int, byWhite: Boolean): Boolean {
        val f = sq % 8
        val r = sq / 8
        fun piece(df: Int, dr: Int): Char {
            val nf = f + df
            val nr = r + dr
            return if (nf in 0..7 && nr in 0..7) board[nr * 8 + nf] else ' '
        }
        fun own(c: Char) = if (byWhite) c.uppercaseChar() else c.lowercaseChar()

        // Pions
        val pawnDir = if (byWhite) -1 else 1
        if (piece(-1, pawnDir) == own('p') || piece(1, pawnDir) == own('p')) return true
        // Cavaliers
        for ((df, dr) in KNIGHT) if (piece(df, dr) == own('n')) return true
        // Roi
        for ((df, dr) in KING) if (piece(df, dr) == own('k')) return true
        // Pièces à longue portée
        for ((df, dr) in KING) {
            val diagonal = df != 0 && dr != 0
            var k = 1
            while (true) {
                val c = piece(df * k, dr * k)
                if (c == ' ') break
                if (c != '.') {
                    if (c == own('q') || c == own(if (diagonal) 'b' else 'r')) return true
                    break
                }
                k++
            }
        }
        return false
    }

    fun inCheck(white: Boolean): Boolean {
        val k = kingSquare(white)
        return k >= 0 && isAttacked(k, !white)
    }

    /** Retourne un message d'erreur si la position ne peut pas être analysée, null sinon. */
    fun validate(): String? {
        if (board.count { it == 'K' } != 1 || board.count { it == 'k' } != 1) {
            return "Il faut exactement un roi de chaque couleur."
        }
        for (file in 0 until 8) {
            val a = at(file, 0)
            val b = at(file, 7)
            if (a == 'P' || a == 'p' || b == 'P' || b == 'p') return "Pion sur la première ou la dernière rangée."
        }
        if (board.count { it == 'P' } > 8 || board.count { it == 'p' } > 8) return "Plus de 8 pions d'une couleur."
        if (board.count { it.isUpperCase() } > 16 || board.count { it.isLowerCase() } > 16) {
            return "Plus de 16 pièces d'une couleur."
        }
        if (inCheck(!whiteToMove)) {
            return "Le camp qui n'a pas le trait est en échec."
        }
        return null
    }

    /** Coup UCI (« g1f3 ») vers une notation lisible en français (« Cg1-f3 », « O-O », « e7-e8=D »). */
    fun describe(uci: String): String {
        if (uci.length < 4) return uci
        val from = squareIndex(uci.substring(0, 2)) ?: return uci
        val to = squareIndex(uci.substring(2, 4)) ?: return uci
        val p = board[from]
        val target = board[to]
        if (p.lowercaseChar() == 'k' && kotlin.math.abs(from % 8 - to % 8) == 2) {
            return if (to % 8 == 6) "O-O" else "O-O-O"
        }
        val isPawn = p.lowercaseChar() == 'p'
        val capture = target != '.' || (isPawn && from % 8 != to % 8)
        val sb = StringBuilder()
        sb.append(FRENCH[p.lowercaseChar()] ?: "")
        sb.append(uci, 0, 2)
        sb.append(if (capture) 'x' else '-')
        sb.append(uci, 2, 4)
        if (uci.length >= 5) sb.append('=').append(FRENCH[uci[4].lowercaseChar()] ?: uci[4].toString())
        return sb.toString()
    }

    companion object {
        private val KNIGHT = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        private val KING = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        private val FRENCH = mapOf('k' to "R", 'q' to "D", 'r' to "T", 'b' to "F", 'n' to "C", 'p' to "")

        fun squareIndex(name: String): Int? {
            if (name.length != 2) return null
            val f = name[0] - 'a'
            val r = name[1] - '1'
            return if (f in 0..7 && r in 0..7) r * 8 + f else null
        }
    }
}
