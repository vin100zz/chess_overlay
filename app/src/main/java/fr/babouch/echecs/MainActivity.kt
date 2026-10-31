package fr.babouch.echecs

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Écran principal : permissions, démarrage de la capture et réglages. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var restrictedHelp: TextView
    private lateinit var appInfoButton: Button
    private lateinit var calibrationStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            addView(col)
            clipToPadding = false
            setBackgroundColor(Color.WHITE)
        }
        // Android 15+ affiche l'appli « bord à bord » : on laisse la place aux barres système
        // (barre d'état en haut, barre de navigation en bas) pour que rien ne soit caché.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            // Icônes sombres dans les barres système, lisibles sur le fond blanc.
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }

        TextView(this).apply {
            text = "♞ Échecs Overlay"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF2E7D32.toInt())
            col.addView(this)
        }

        fun heading(t: String) = TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
            col.addView(this)
        }
        fun para(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setPadding(0, dp(2), 0, dp(6))
            col.addView(this)
        }
        fun button(t: String, action: () -> Unit) = Button(this).apply {
            text = t
            setAllCaps(false)
            setOnClickListener { action() }
            col.addView(this)
        }

        heading("1. Afficher par-dessus les autres applis")
        overlayStatus = para("")
        overlayButton = button("Autoriser l'affichage par-dessus") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        restrictedHelp = para(
            "Si Android répond « L'accès a été refusé à cette appli » : touchez le bouton ci-dessous, " +
                "puis le menu ⋮ en haut à droite → « Autoriser les paramètres restreints » (confirmez avec " +
                "votre code). Revenez ensuite ici et touchez à nouveau « Autoriser l'affichage par-dessus ».",
        )
        appInfoButton = button("Ouvrir les infos de l'appli") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        heading("2. Lancer la capture")
        para(
            "Android demande l'autorisation d'enregistrer l'écran : choisissez « Tout l'écran ». " +
                "Une bulle ♞ apparaît ensuite par-dessus vos applis.",
        )
        button("▶ Démarrer") { start() }
        button("■ Arrêter") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }

        heading("3. Calibrer (une fois par jeu de pièces)")
        para(
            "Dans votre appli d'échecs, affichez une partie à la position initiale (nouvelle partie ou " +
                "échiquier d'analyse), touchez la bulle ♞ puis « Calibrer ». L'appli apprend alors le dessin " +
                "des pièces. Vous pouvez calibrer plusieurs applis : elles sont toutes mémorisées.",
        )
        calibrationStatus = para("")
        button("Oublier les calibrations") {
            TemplateStore.clear(this)
            updateStatus()
        }

        heading("4. Analyser")
        para(
            "Touchez la bulle ♞ : l'échiquier est lu, Stockfish calcule, et les meilleurs coups s'affichent " +
                "en flèches (vert = meilleur, puis bleu, puis orange). Par défaut c'est au camp du bas de jouer ; " +
                "« ⇄ Trait » change le camp au trait, « ⇅ Sens » retourne l'échiquier si le sens a été mal deviné. " +
                "Faites glisser la bulle pour la déplacer.",
        )

        heading("Réglages")
        para("Temps de réflexion du moteur")
        radio(col, listOf("0,5 s" to 500, "1,5 s" to 1500, "3 s" to 3000, "8 s" to 8000), prefs.moveTimeMs) {
            prefs.moveTimeMs = it
        }
        para("Nombre de coups proposés")
        radio(col, listOf("1" to 1, "2" to 2, "3" to 3), prefs.multiPv) { prefs.multiPv = it }
        para("Sens de l'échiquier")
        radio(col, listOf("Auto" to 0, "Blancs en bas" to 1, "Noirs en bas" to 2), prefs.orientation) {
            prefs.orientation = it
        }

        TextView(this).apply {
            text = "\nÀ utiliser pour analyser vos parties, des problèmes ou des vidéos — " +
                "pas pendant une partie en ligne contre un adversaire humain (c'est de la triche et " +
                "les sites ferment les comptes concernés).\n\nMoteur : Stockfish (GPLv3)."
            textSize = 13f
            setTextColor(Color.GRAY)
            col.addView(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val ok = Settings.canDrawOverlays(this)
        overlayStatus.text = if (ok) "✓ Autorisé" else "✗ Pas encore autorisé"
        overlayButton.visibility = if (ok) View.GONE else View.VISIBLE
        restrictedHelp.visibility = if (ok) View.GONE else View.VISIBLE
        appInfoButton.visibility = if (ok) View.GONE else View.VISIBLE
        val n = TemplateStore.load(this).size / 64
        calibrationStatus.text = if (n == 0) "✗ Aucune calibration" else "✓ $n calibration(s) en mémoire"
    }

    private fun radio(parent: LinearLayout, options: List<Pair<String, Int>>, current: Int, onChange: (Int) -> Unit) {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((label, value) in options) {
            val rb = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                setPadding(0, 0, dp(12), 0)
            }
            group.addView(rb)
            if (value == current) rb.isChecked = true
            rb.setOnCheckedChangeListener { _, checked -> if (checked) onChange(value) }
        }
        parent.addView(group)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorisez d'abord l'affichage par-dessus les autres applis.", Toast.LENGTH_LONG).show()
            return
        }
        if (OverlayService.running) {
            Toast.makeText(this, "Déjà démarré : utilisez la bulle ♞.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestCapture() // la notification est utile mais pas indispensable
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("API Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Capture d'écran refusée.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(intent)
        Toast.makeText(this, "C'est parti : ouvrez votre appli d'échecs et touchez ♞.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIF = 2
    }
}
