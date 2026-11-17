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

Détail par version : [CHANGELOG.md](CHANGELOG.md).

## Installer

- **Obtainium** : ajouter l'adresse du dépôt GitHub ; les mises à jour arrivent avec chaque release.
- **GitHub** : télécharger `xfiles-x.y.z.apk` dans la page *Releases* et l'installer.

Android 11 minimum. Au premier lancement, XFiles demande l'autorisation « Accès à tous les fichiers »,
indispensable à un gestionnaire de fichiers, puis, lors de la première copie longue, l'autorisation des notifications
(facultative, pour voir la progression).

## Feuille de route

- Autres formats d'archive : 7z, tar, gz, puis RAR.
- Dossier `Android/data` via Shizuku.
- Stockage en ligne : WebDAV (Synology, pCloud), OneDrive, Google Drive.

## Compiler

```bash
./gradlew testDebugUnitTest assembleDebug
```

L'APK debug s'installe à côté de la version release (identifiant `….xfiles.debug`).
L'APK release est optimisé par R8 (code et ressources inutilisés retirés).

Pré-requis : JDK 17 ou plus. Android Gradle Plugin 9, Kotlin, Jetpack Compose (Material 3) ;
compilé avec le SDK 37, cible l'API 36, minimum l'API 30.

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

## Licence

GPL-3.0, voir [LICENSE](LICENSE).
