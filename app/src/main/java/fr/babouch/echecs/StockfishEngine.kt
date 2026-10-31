package fr.babouch.echecs

import fr.babouch.echecs.core.EngineLine
import fr.babouch.echecs.core.UciParser
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Pilote le binaire Stockfish (embarqué dans l'APK sous le nom libstockfish.so) via le protocole UCI.
 * À n'utiliser que depuis un seul thread.
 */
class StockfishEngine(private val path: String) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>()

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = ProcessBuilder(path).redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()
        lines.clear()
        thread(isDaemon = true, name = "stockfish-sortie") {
            try {
                p.inputStream.bufferedReader().forEachLine { lines.put(it) }
            } catch (_: IOException) {
            }
            lines.put(EOF)
        }
        send("uci")
        waitFor("uciok", 15_000)
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 64")
    }

    private fun send(cmd: String) {
        val w = writer ?: throw IOException("Moteur non démarré")
        w.write(cmd)
        w.newLine()
        w.flush()
    }

    private fun next(deadline: Long): String {
        val remaining = deadline - System.currentTimeMillis()
        val line = if (remaining > 0) lines.poll(remaining, TimeUnit.MILLISECONDS) else null
        if (line == null) {
            stop()
            throw IOException("le moteur ne répond pas")
        }
        if (line === EOF) {
            stop()
            throw IOException("le moteur s'est arrêté")
        }
        return line
    }

    private fun waitFor(prefix: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!next(deadline).startsWith(prefix)) {
            // on ignore les autres lignes
        }
    }

    /** Analyse la position et renvoie les meilleures lignes, triées (la meilleure en premier). */
    fun analyze(fen: String, multiPv: Int, moveTimeMs: Int): List<EngineLine> {
        ensureStarted()
        send("setoption name MultiPV value $multiPv")
        send("isready")
        waitFor("readyok", 10_000)
        send("position fen $fen")
        send("go movetime $moveTimeMs")
        val best = HashMap<Int, EngineLine>()
        val deadline = System.currentTimeMillis() + moveTimeMs + 15_000
        while (true) {
            val line = next(deadline)
            if (line.startsWith("bestmove")) break
            val info = UciParser.parseInfo(line) ?: continue
            if (info.multipv in 1..multiPv) best[info.multipv] = info
        }
        return best.values.sortedBy { it.multipv }
    }

    fun stop() {
        try {
            writer?.let {
                it.write("quit\n")
                it.flush()
            }
        } catch (_: IOException) {
        }
        process?.destroy()
        process = null
        writer = null
    }

    private companion object {
        // Marqueur de fin de flux (comparé par référence).
        val EOF = String(charArrayOf('<', 'E', 'O', 'F', '>'))
    }
}
