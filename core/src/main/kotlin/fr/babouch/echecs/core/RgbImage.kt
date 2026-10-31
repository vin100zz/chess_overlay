package fr.babouch.echecs.core

/**
 * Image en mémoire, pixels au format ARGB (0xAARRGGBB), rangés ligne par ligne.
 * Indépendante d'Android pour pouvoir être testée sur la JVM.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "Image vide" }
        require(pixels.size >= width * height) { "Tableau de pixels trop petit" }
    }

    fun argb(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= width) width - 1 else x
        val cy = if (y < 0) 0 else if (y >= height) height - 1 else y
        return pixels[cy * width + cx]
    }

    /** Couleur moyenne d'un carré de (2*radius+1)² pixels centré sur (x, y). Résultat dans out[0..2] (R, G, B). */
    fun average(x: Float, y: Float, radius: Int, out: FloatArray) {
        val cx = x.toInt()
        val cy = y.toInt()
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val p = argb(cx + dx, cy + dy)
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        out[0] = r.toFloat() / n
        out[1] = g.toFloat() / n
        out[2] = b.toFloat() / n
    }
}

internal fun luminance(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

internal fun dist2(a: FloatArray, b: FloatArray): Float {
    val dr = a[0] - b[0]
    val dg = a[1] - b[1]
    val db = a[2] - b[2]
    return dr * dr + dg * dg + db * db
}
