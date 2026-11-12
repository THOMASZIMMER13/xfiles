# XFiles

Gestionnaire de fichiers Android pensé d'abord pour TalkBack, dans l'esprit de l'app Fichiers d'iOS.

> Nom de travail. Le nom affiché pourra changer ; l'identifiant `io.github.thomaszimmer13.xfiles` restera le même.

## État

Étape 0 : squelette de l'app, trois onglets en bas (Récents, Favoris, Explorer), CI.

## Pré-requis techniques

- Android 11 (API 30) minimum, cible Android 16 (API 36), compilé avec le SDK 37.
- Kotlin + Jetpack Compose (Material 3), Android Gradle Plugin 9, Gradle 9.

## Compiler

```bash
./gradlew assembleDebug
```

L'APK debug s'installe à côté de la version release (identifiant `….xfiles.debug`).

## CI et signature

Le workflow `.github/workflows/build.yml` produit à chaque push :

- un APK **debug** (toujours) ;
- un APK **release signé** si les secrets de signature sont présents ;
- une **GitHub Release** avec l'APK signé quand on pousse un tag `v*` (ex. `v0.1.0`).

Secrets à créer dans *Settings → Secrets and variables → Actions* :

| Secret | Contenu |
|---|---|
| `XFILES_KEYSTORE_BASE64` | Le fichier `.jks` encodé en base64 |
| `XFILES_KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `XFILES_KEY_ALIAS` | Alias de la clé |
| `XFILES_KEY_PASSWORD` | Mot de passe de la clé |

**Sauvegardez la clé** : sans elle, les mises à jour ne s'installeront plus par-dessus l'app existante.

## Licence

GPL-3.0, voir [LICENSE](LICENSE).
