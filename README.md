# Échecs Overlay (Android)

Appli Android personnelle : elle lit l'échiquier affiché à l'écran (dans n'importe quelle appli ou vidéo),
reconnaît la position, la fait analyser par **Stockfish** directement sur le téléphone, et dessine les
meilleurs coups en flèches par-dessus l'échiquier.

> À utiliser pour analyser ses parties, des problèmes, des vidéos… pas pendant une partie en ligne
> contre un humain : c'est de la triche, et les sites (Chess.com, Lichess…) la détectent et ferment les comptes.

## Installer sur le téléphone

1. Chaque push sur la branche `main` lance le workflow GitHub Actions **« APK Android »**,
   qui compile l'APK.
2. Sur le téléphone, ouvrez https://github.com/vin100zz/chess_overlay/releases/download/echecs-apk/echecs-overlay.apk,
   ou la page **Releases** du dépôt → **« Échecs Overlay (dernière version) »** →
   téléchargez `echecs-overlay.apk`. (L'APK est aussi disponible en « artifact » sur la page du workflow.)
3. Ouvrez le fichier ; Android demande d'autoriser l'installation depuis le navigateur ou le gestionnaire
   de fichiers : acceptez. Les mises à jour s'installent par-dessus l'ancienne version (même clé de signature).

Le téléphone doit être en ARM 64 bits (quasiment tous les téléphones depuis 2017) et avoir Android 8 ou plus.

## Utilisation

1. **Autoriser l'affichage par-dessus les autres applis** (bouton dans l'appli).
   Si Android répond « L'accès a été refusé à cette appli » (restriction des applis installées hors
   Play Store, Android 13+) : Paramètres → Applis → Échecs Overlay → menu ⋮ → « Autoriser les paramètres
   restreints », puis réessayer.
2. **▶ Démarrer** → Android demande l'autorisation d'enregistrer l'écran : choisir **« Tout l'écran »**.
   Une bulle ♞ apparaît (déplaçable en la faisant glisser).
3. **Calibrer, une fois par jeu de pièces** : dans l'appli d'échecs, afficher une position initiale
   (nouvelle partie, échiquier d'analyse…), toucher ♞ puis **Calibrer**. L'appli apprend le dessin des pièces.
   Plusieurs applis ou thèmes peuvent être calibrés : toutes les calibrations sont utilisées.
4. **Analyser** : toucher ♞. Flèche verte = meilleur coup, bleue = 2ᵉ, orange = 3ᵉ. Le panneau affiche, pour
   chaque coup, une pastille de la couleur de sa flèche et son évaluation (point de vue des blancs).
   - Par défaut, c'est au camp **du bas** de jouer. **⇄ Trait** change le camp au trait.
   - **⇅ Sens** retourne l'échiquier si le sens (blancs en bas / noirs en bas) a été mal deviné.
   - **✕** efface les flèches.

Réglages dans l'appli : temps de réflexion (0,5 à 8 s), nombre de coups (1 à 3), sens de l'échiquier
(auto / forcé).

## Comment ça marche

| Étape | Fichier |
|---|---|
| Capture de l'écran (MediaProjection, service au premier plan), bulle et panneau flottants | `app/.../OverlayService.kt` |
| Recherche de l'échiquier : le carré dont les coins des cases forment le meilleur damier clair/foncé, puis calage précis sur les bords des cases | `core/.../BoardDetector.kt` |
| Reconnaissance des pièces : « signature » de chaque case (forme de la pièce une fois le fond retiré, et sa couleur), comparée aux cases apprises sur la position initiale | `core/.../PieceRecognizer.kt` |
| Position, FEN, roques déduits, vérification de légalité, notation française | `core/.../Chess.kt` |
| Dialogue UCI avec Stockfish (binaire officiel `sf_17.1` ARM64, téléchargé à la compilation) | `app/.../StockfishEngine.kt`, `core/.../Uci.kt` |
| Dessin des flèches | `app/.../ArrowView.kt` |

Le module `core` n'utilise pas Android : ses tests (`./gradlew :core:test`) tournent sur un PC.

## Limites de cette première version

- Échiquiers **2D vus de face** uniquement (applis, sites, vidéos), en mode portrait.
- Le trait, les droits de roque et la prise en passant ne se voient pas sur une image : le trait est
  supposé au camp du bas (bouton ⇄ sinon), les roques sont déduits de la position des rois et des tours,
  la prise en passant est ignorée.
- Une pièce en cours de déplacement ou une flèche dessinée par l'appli d'échecs peut gêner la lecture.

## Compiler soi-même

Android Studio (ou JDK 17 + SDK Android) : `./gradlew :app:assembleDebug`.
L'APK se trouve dans `app/build/outputs/apk/debug/`.

Stockfish est sous licence GPLv3 (https://stockfishchess.org).
