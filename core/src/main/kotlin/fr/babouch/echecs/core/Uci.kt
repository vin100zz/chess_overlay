package fr.babouch.echecs.core

/** Une ligne d'analyse renvoyée par le moteur (score du point de vue du camp qui a le trait). */
data class EngineLine(
    val multipv: Int,
    val depth: Int,
    val cp: Int?,
    val mate: Int?,
    val pv: List<String>,
) {
    /** Évaluation lisible, du point de vue des blancs : « +0.35 », « -1.20 », « #3 », « #-2 ». */
    fun scoreText(whiteToMove: Boolean): String {
        val sign = if (whiteToMove) 1 else -1
        if (mate != null) {
            val m = mate * sign
            return if (m > 0) "#$m" else "#-${-m}"
        }
        val v = (cp ?: 0) * sign
        val abs = kotlin.math.abs(v)
        return (if (v >= 0) "+" else "-") + "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}

object UciParser {
    /** Analyse une ligne « info ... pv ... » de Stockfish ; null si elle ne contient pas de variante. */
    fun parseInfo(line: String): EngineLine? {
        val t = line.trim().split(Regex("\\s+"))
        if (t.isEmpty() || t[0] != "info") return null
        var depth = 0
        var multipv = 1
        var cp: Int? = null
        var mate: Int? = null
        var pv: List<String>? = null
        var i = 1
        while (i < t.size) {
            when (t[i]) {
                "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i++ }
                "multipv" -> { multipv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i++ }
                "score" -> {
                    val kind = t.getOrNull(i + 1)
                    val v = t.getOrNull(i + 2)?.toIntOrNull()
                    if (kind == "cp") cp = v else if (kind == "mate") mate = v
                    i += 2
                }
                "pv" -> { pv = t.subList(i + 1, t.size); i = t.size }
                "string" -> i = t.size
            }
            i++
        }
        if (pv.isNullOrEmpty()) return null
        return EngineLine(multipv, depth, cp, mate, pv)
    }
}
