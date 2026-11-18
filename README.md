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

- Onglets **Récents**, **Favoris**, **Explorer** ; mémoire interne, carte SD, clé USB.
- Créer, renommer, copier, déplacer, partager, compresser ; **sélection multiple**.
- **Corbeille** par volume avec restauration, purge après 30 jours.
- **Recherche** par nom (sans tenir compte des majuscules ni des accents).
- **Archives zip** : parcourir, ouvrir, extraire, créer.
- Opérations longues en **arrière-plan** avec notification et annulation.
- **Onglets** et **double panneau** (facultatifs), copie directe d'un panneau à l'autre.
- **Serveurs** (SMB, SFTP, WebDAV, FTP), **comptes en ligne** (Drive, OneDrive, Dropbox…), **coffres chiffrés**.
- **Verrouillage** par empreinte ou code ; dossiers **Android/data** avec Shizuku (facultatif).

Détail par version : [CHANGELOG.md](CHANGELOG.md).

## Installer

- **Obtainium** : ajouter l'adresse du dépôt GitHub ; les mises à jour arrivent avec chaque release.
- **GitHub** : télécharger `xfiles-x.y.z.apk` dans la page *Releases* et l'installer.

Android 11 minimum. Au premier lancement, XFiles demande l'autorisation « Accès à tous les fichiers »,
indispensable à un gestionnaire de fichiers, puis, lors de la première copie longue, l'autorisation des notifications
(facultative, pour voir la progression).

## Feuille de route

- Format d'archive WIM (libarchive ne le lit pas : il faudrait wimlib).
- RAR chiffrés (libarchive sait les reconnaître, pas les déchiffrer).

## Compiler

```bash
./gradlew testDebugUnitTest assembleDebug
```

L'APK debug s'installe à côté de la version release (identifiant `….xfiles.debug`).
L'APK release est optimisé par R8 (code et ressources inutilisés retirés).

Pré-requis : JDK 17 ou plus. Android Gradle Plugin 9, Kotlin, Jetpack Compose (Material 3) ;
compilé avec le SDK 37, cible l'API 36, minimum l'API 30.
Le pont rclone demande Go et le NDK Android ; libarchive (RAR, ISO) est compilée depuis ses sources
(version et commit figés dans `app/native/build.sh`) avec le NDK 29.0.14206865, cmake et ninja.

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
- **Shizuku** : facultatif ; il ne sert qu'à lire et écrire dans Android/data, obb et media.

## Licence

GPL-3.0, voir [LICENSE](LICENSE).
