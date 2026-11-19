# XFiles

Gestionnaire de fichiers Android pensé d'abord pour **TalkBack**, dans l'esprit de l'app Fichiers d'iOS.
Logiciel libre (GPL-3.0), sans publicité ni pistage.

## Principes d'accessibilité

- Chaque fichier ou dossier est **un seul élément TalkBack**, lu dans l'ordre : nom, type, taille, date.
- Toutes les actions sont des **actions personnalisées TalkBack** (balayer vers le haut puis vers le bas, ou le menu des actions).
- Les annonces sont **courtes et jamais répétées** : un bilan à la fin d'une opération, pas de bavardage pendant.
- Les titres sont marqués comme **titres**, l'ordre de balayage est logique, rien de décoratif ne prend le focus.
- **Tout est possible sans voir l'écran**, y compris la sélection multiple, les conflits de nom et la corbeille.
- Le focus est toujours replacé à un endroit utile : élément d'où l'on revient, élément créé, voisin d'un élément supprimé.

## Fonctions

- Onglets **Récents**, **Favoris**, **Explorer** ; mémoire interne, carte SD, clé USB (annoncées au branchement et au retrait), catégories (Images, Vidéos, Audio, Documents, Archives, APK).
- Créer, renommer (aussi **par lot**), copier, déplacer, dupliquer, partager, compresser ; **sélection multiple**.
- **Corbeille** par volume avec restauration, purge après 30 jours.
- **Recherche** par nom (sans tenir compte des majuscules ni des accents) ; **nettoyage** (gros fichiers, doublons, anciens téléchargements).
- **Archives** : parcourir, ouvrir, extraire, comme des dossiers. Formats lus :
  - zip, 7z, tar, gz, bz2 et xz ;
  - **RAR 4 et RAR 5**, images **ISO**, par libarchive (licence BSD) compilée avec le NDK ; les RAR chiffrés sont reconnus mais pas extraits.

  Création en zip (AES-256 facultatif), 7z ou tar.gz. Garde-fou contre les archives piégées : ratio, taille et chemins hors du dossier refusés.
- Opérations longues en **arrière-plan** avec notification et annulation. L'avancement est lu par paliers de 10 %. L'espace libre et la limite de 4 Go du FAT32 sont vérifiés avant de commencer.
- **Onglets** et **double panneau** (facultatifs), copie directe d'un panneau à l'autre.
- **Lecteur audio et vidéo** (FFmpeg, sous-titres, audiodescription) et **lecteur de documents** (txt, PDF, docx, odt, epub), un paragraphe par arrêt TalkBack ; éditeur de texte.
- **Reconnaissance de texte** (PaddleOCR, sur le téléphone) et **scanner de documents** guidé à la voix.
- **Serveurs** :
  - SMB 2/3 (signature, et chiffrement si le serveur le propose) ;
  - SFTP, avec l'empreinte de la clé vérifiée ;
  - WebDAV, avec HTTP en clair limité au réseau local ;
  - FTP et FTPS.
- **Comptes en ligne** : Drive, OneDrive, Dropbox, Box, pCloud, Yandex, par rclone (connexion dans le navigateur).
- **Coffres chiffrés**, compatibles avec rclone crypt.
- **Verrouillage** par empreinte ou code ; dossiers **Android/data**, obb et media avec Shizuku (facultatif).

Détail par version : [CHANGELOG.md](CHANGELOG.md).

## Installer

- **Obtainium** : ajouter l'adresse du dépôt GitHub ; les mises à jour arrivent avec chaque release.
- **GitHub** : télécharger `xfiles-x.y.z.apk` dans la page *Releases* et l'installer.

Android 11 minimum. Au premier lancement, XFiles demande l'autorisation « Accès à tous les fichiers »,
indispensable à un gestionnaire de fichiers, puis, lors de la première copie longue, l'autorisation des notifications
(facultative, pour voir la progression).

## Feuille de route

- Format d'archive **WIM** : libarchive ne le lit pas, il faudrait intégrer wimlib (LGPL-3.0) compilée avec le NDK.
- **RAR chiffrés** : libarchive les reconnaît mais ne les déchiffre pas, et le code UnRAR n'est pas compatible avec la GPL.
- Recette sur un téléphone Android 11 ou 12 (FLAG_SECURE, aperçu des applications récentes).

## Compiler

Pré-requis :
- JDK 17 ou plus et le SDK Android (compilé avec le SDK 37, cible l'API 36, minimum l'API 30).
- NDK 29.0.14206865 : `sdkmanager "ndk;29.0.14206865"`, ou la variable `ANDROID_NDK_HOME`.
- Go (version indiquée dans `rclone/go.mod`), pour le pont rclone compilé par gomobile.
- cmake et ninja, pour libarchive. Ses sources sont téléchargées et vérifiées par `app/native/build.sh` (étiquette et commit figés).

L'application utilise Android Gradle Plugin 9, Kotlin et Jetpack Compose (Material 3).

```bash
# Tests du pont rclone (avec le détecteur de concurrence)
cd rclone && CGO_ENABLED=1 go test -race ./... && cd ..

# Tests JVM, lint, puis APK
./gradlew testDebugUnitTest lintDebug
./gradlew assembleDebug        # ou assembleRelease
```

Les tâches `buildRclone` et la compilation de libarchive (pour chaque ABI, plus une version pour
la machine utilisée par les tests JVM) sont lancées automatiquement par Gradle.

L'APK debug s'installe à côté de la version release (identifiant `….xfiles.debug`). L'APK release
est optimisé par R8 et décliné par ABI : arm64, armv7, x86_64, plus un APK universel.

Les règles à respecter dans chaque modification (accessibilité, sécurité des fichiers, tests)
sont dans [CLAUDE.md](CLAUDE.md).

## CI, signature et publication

Le workflow `.github/workflows/build.yml` produit à chaque push un APK debug, et un APK release signé si les secrets
de signature sont présents. Pousser un tag `vX.Y.Z` publie une **GitHub Release** avec l'APK signé ; ses notes sont
reprises de la section `## [X.Y.Z]` du CHANGELOG.

| Secret | Contenu |
|---|---|
| `XFILES_KEYSTORE_BASE64` | Le fichier `.jks` encodé en base64 |
| `XFILES_KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `XFILES_KEY_ALIAS` | Alias de la clé |
| `XFILES_KEY_PASSWORD` | Mot de passe de la clé |
| `XFILES_GDRIVE_CLIENT_SECRET` | Secret de l'identifiant Google Drive (facultatif ; une variable du même nom reste acceptée) |

Garde-fous de la CI : actions épinglées par SHA de commit, droits en lecture seule sauf pour le job
de publication, clé de signature effacée en fin de job, **lint obligatoire** (une erreur arrête la
construction) et **contrôle des licences** (`./gradlew checkLicenses`, rapport dans
`app/build/reports/licences.txt`) : toute dépendance sous licence incompatible avec la GPL-3.0,
comme la licence UnRAR, fait échouer la construction.

Avant chaque version : [protocole de recette sur téléphone](docs/RECETTE.md), avec TalkBack, et sa [version rapide](docs/RECETTE-RAPIDE.md) (15 minutes).

**Sauvegardez la clé** : sans elle, les mises à jour ne s'installeront plus par-dessus l'app existante.

## Comptes en ligne (rclone)

Google Drive, OneDrive, Dropbox, Box, pCloud et Yandex Disk passent par [rclone](https://rclone.org/)
(licence MIT), intégré à l'application : le module Go du dossier `rclone/` est compilé en bibliothèque
Android par gomobile (tâche Gradle `buildRclone`, lancée automatiquement). Pré-requis pour compiler :
Go (version indiquée dans `rclone/go.mod`) et le NDK Android (`sdkmanager "ndk;29.0.14206865"`,
ou la variable `ANDROID_NDK_HOME`). Le pont s'inspire de rcbridge de [RSAF](https://github.com/chenxiaolong/RSAF) (GPL-3.0).

La connexion se fait dans le navigateur ; les jetons sont gardés dans la configuration rclone,
chiffrée par un mot de passe lui-même chiffré par le Keystore d'Android.

**Google Drive** : l'identifiant partagé de rclone est retiré par Google en 2026, il faut donc un
identifiant d'application Google de type « Application de bureau » (console Google Cloud, API Google
Drive activée, champ d'application `.../auth/drive`). Celui d'XFiles se règle par les variables du
dépôt `XFILES_GDRIVE_CLIENT_ID` et `XFILES_GDRIVE_CLIENT_SECRET` (`gh variable set …`) ; chacun peut
aussi saisir le sien à l'ajout du compte. Tant que l'application Google n'est pas validée par Google,
elle est limitée à 100 comptes et affiche un avertissement à la connexion.

## Sécurité

- **Mots de passe des serveurs** : chiffrés (AES-256-GCM) par une clé du Keystore d'Android, qui ne quitte pas le téléphone.
- **Comptes en ligne** : XFiles ne voit jamais le mot de passe du compte, seulement un jeton révocable ; la configuration rclone qui le contient est chiffrée.
- **Coffres** : rclone crypt (contenus et noms) ; le mot de passe n'est enregistré que scellé par l'empreinte, si on le demande.
- **Aucune sauvegarde** des données de l'app (sauvegarde Google et transfert d'appareil désactivés).
- **Shizuku** : facultatif ; il ne sert qu'à lire et écrire dans Android/data, obb et media, sans jamais supprimer ces racines.
- **Fichiers** :
  - les suppressions et les parcours ne suivent jamais les liens symboliques ;
  - les noms venus d'un serveur ou d'une archive ne peuvent pas sortir du dossier de destination ;
  - les raccourcis venus d'autres apps n'ouvrent qu'un dossier du stockage partagé.
- **Coffres** : à la fermeture, effacement des copies déchiffrées, du texte reconnu, des aperçus, des récents et des positions de lecture.
- **Média d'un coffre** : écran verrouillé et notification affichent « Fichier du coffre ».
- **Applications récentes** : aperçu masqué quand l'app est verrouillée ou qu'un coffre est ouvert.
- **Réseau** : HTTP en clair seulement vers le réseau local ; analyse WebDAV protégée contre XXE.

## Licence

GPL-3.0, voir [LICENSE](LICENSE).
