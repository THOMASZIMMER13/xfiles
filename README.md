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

## Comptes en ligne : enregistrer l'application

Google Drive, Dropbox et OneDrive n'acceptent une app qu'une fois enregistrée chez eux. Ces identifiants sont
publics (pas de secret) ; sans eux, le service est indiqué « non disponible dans cette version ».

**Dropbox** (<https://www.dropbox.com/developers/apps>) : « Create app », accès « Full Dropbox ».
Onglet *Permissions* : cocher `files.metadata.read`, `files.content.read`, `files.content.write`,
`account_info.read`. Onglet *Settings*, « Redirect URIs » : ajouter `xfiles-auth://dropbox` (et
`xfiles-auth-debug://dropbox` pour l'APK debug). Copier l'« App key » dans la variable du dépôt `XFILES_DROPBOX_KEY`.

**OneDrive** (<https://entra.microsoft.com>, *Inscriptions d'applications*) : « Nouvelle inscription », types de
comptes « comptes dans un annuaire organisationnel et comptes Microsoft personnels ». Plateforme « Applications
mobiles et de bureau », URI de redirection `xfiles-auth://onedrive` (et `xfiles-auth-debug://onedrive`).
Autorisations Microsoft Graph déléguées : `Files.ReadWrite`, `User.Read`, `offline_access`. Copier l'« ID
d'application (client) » dans la variable `XFILES_ONEDRIVE_ID`.

**Google Drive** (<https://console.cloud.google.com>) : créer un projet, activer « Google Drive API ».
Écran de consentement OAuth : type « Externe », ajouter le champ d'application `.../auth/drive`, puis
« Publier l'application » (sinon l'accès expire au bout de 7 jours). Identifiants : « ID client OAuth »
de type **Android**, nom de package `io.github.thomaszimmer13.xfiles`, empreinte SHA-1 du certificat de signature
(`keytool -printcert -jarfile xfiles.apk`). Rien à copier dans le dépôt : Google reconnaît l'app à sa signature.
Pour l'APK debug, un second ID client avec le package `io.github.thomaszimmer13.xfiles.debug` et la clé debug.

Les variables se règlent avec `gh variable set XFILES_DROPBOX_KEY -b <clé>` (de même pour `XFILES_ONEDRIVE_ID`).

## Licence

GPL-3.0, voir [LICENSE](LICENSE).
