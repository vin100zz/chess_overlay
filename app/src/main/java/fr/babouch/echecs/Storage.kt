package fr.babouch.echecs

import android.content.Context
import fr.babouch.echecs.core.PieceRecognizer
import fr.babouch.echecs.core.Template
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Réglages de l'application. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("echecs", Context.MODE_PRIVATE)

    /** Temps de réflexion du moteur, en millisecondes. */
    var moveTimeMs: Int
        get() = sp.getInt("movetime", 1500)
        set(v) = sp.edit().putInt("movetime", v).apply()

    /** Nombre de coups proposés (1 à 3). */
    var multiPv: Int
        get() = sp.getInt("multipv", 3)
        set(v) = sp.edit().putInt("multipv", v).apply()

    /** 0 = automatique, 1 = blancs en bas, 2 = noirs en bas. */
    var orientation: Int
        get() = sp.getInt("orientation", 0)
        set(v) = sp.edit().putInt("orientation", v).apply()

    /** Sens de l'échiquier lors de la dernière calibration (sert quand le sens automatique hésite). */
    var lastCalibrationWhiteAtBottom: Boolean
        get() = sp.getBoolean("calib_white_bottom", true)
        set(v) = sp.edit().putBoolean("calib_white_bottom", v).apply()
}

/**
 * Modèles de pièces appris lors des calibrations. On garde les dernières calibrations
 * (une par jeu de pièces / application utilisée) et on les utilise toutes ensemble.
 */
object TemplateStore {
    private const val MAGIC = 0x45434831 // "ECH1"
    private const val MAX_TEMPLATES = 64 * 6

    private fun file(context: Context) = File(context.filesDir, "modeles.bin")

    @Synchronized
    fun load(context: Context): List<Template> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return emptyList()
                val size = input.readInt()
                if (size != PieceRecognizer.FEATURE_SIZE) return emptyList()
                val count = input.readInt()
                List(count) {
                    val label = input.readChar()
                    val feats = FloatArray(size) { input.readFloat() }
                    Template(label, feats)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, templates: List<Template>): Int {
        val all = (load(context) + templates).takeLast(MAX_TEMPLATES)
        DataOutputStream(file(context).outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(PieceRecognizer.FEATURE_SIZE)
            out.writeInt(all.size)
            for (t in all) {
                out.writeChar(t.label.code)
                for (v in t.features) out.writeFloat(v)
            }
        }
        return all.size / 64
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
