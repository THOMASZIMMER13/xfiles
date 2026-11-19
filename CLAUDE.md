# Règles du dépôt XFiles

XFiles est un gestionnaire de fichiers Android pensé d'abord pour TalkBack. Son auteur est aveugle :
une régression d'accessibilité est un bogue bloquant, au même titre qu'une faille.
Langue : français pour le code commenté, les chaînes, les commits et la documentation.

Chaque commit respecte les règles ci-dessous. Une exception doit être justifiée dans le message de commit.

## Accessibilité (TalkBack)

1. **Un seul nœud par ligne.** Une ligne de fichier ou de dossier est un seul élément TalkBack.
   - Utiliser `clearAndSetSemantics` ou `mergeDescendants`.
   - Ordre de lecture fixe : **nom → type → taille → date** (dossier : nom, « dossier », nombre d'éléments, date).
   - Une taille ou une date inconnue n'est pas lue : jamais « 0 octet » ni « 1970 ».
   - Aucun enfant décoratif ne prend le focus.
2. **Actions personnalisées.** Toute action sur un élément passe par une `CustomAccessibilityAction` (`customActions` dans `semantics`).
   - Pas de bouton caché, pas d'appui long seul, pas de geste propre à l'app.
   - L'action est aussi accessible sans TalkBack, par un menu.
3. **Restauration du focus.** Après chaque changement, le focus va à un endroit prévisible :
   - retour d'un sous-dossier : ce sous-dossier ;
   - suppression : le voisin (le précédent pour le dernier, sinon le titre) ;
   - renommage ou création : l'élément concerné ;
   - ouverture d'un écran : son titre, marqué `heading()` ;
   - double panneau : un seul focus, dans le panneau actif.
4. **Annonces sobres.**
   - Un bilan à la fin, rien pendant l'opération.
   - Progression lue par paliers de 10 % (`ProgressSpeech.tenths`).
   - `liveRegion` seulement pour les erreurs et les résultats.
5. Toute chaîne lue va dans `res/values/strings.xml`, jamais en dur dans le code.

## Sécurité des fichiers

6. **Ne jamais suivre un lien symbolique** pour supprimer ou parcourir.
   - Suppression : `deleteRecursivelyNoFollow(target, boundary)` (`data/fs/SafeFiles.kt`), jamais `File.deleteRecursively()`.
   - Parcours : `walkNoLinks()` et `childrenNoLinks()`, jamais `walkTopDown()` ni `listFiles()` seuls (copie, taille, archive, recherche, analyse).
7. **Confinement canonique.** Avant toute écriture ou suppression dont le chemin vient de l'extérieur (serveur, archive, Intent, Shizuku), vérifier que `canonicalPath == base` ou `canonicalPath.startsWith(base.canonicalPath + File.separator)`.
   - Le séparateur est obligatoire : `/sdcard/Doc` ne doit pas valider `/sdcard/Documents2`.
   - Les racines `Android/{data,obb,media}` ne sont jamais supprimées.
8. **Entrées externes.** Un `Intent` ou un raccourci n'apporte qu'un chemin de dossier, vérifié par `IncomingFolders.safe`. Il n'apporte jamais une route interne (`xfiles:…`), ni un chemin sous `/data`, `cacheDir` ou `filesDir`.
9. **Coffres.**
   - Rien de déchiffré ne survit à la fermeture.
   - Aucun chemin éphémère (`Ephemeral.isEphemeral`) ne va dans les Récents ni dans les positions de lecture.
10. **Coroutines.** Toujours relancer `CancellationException`. Pas de `runCatching` qui l'avale.

## Vérifications obligatoires avant chaque commit

```bash
cd rclone && CGO_ENABLED=1 go test -race ./... && cd ..
./gradlew testDebugUnitTest lintDebug
```

- Les deux commandes doivent réussir, et le lint sans nouvelle erreur.
- Un correctif est accompagné d'un test qui échoue sans lui (Robolectric `@Config(sdk = [35])` pour ce qui touche Android).
- Avant un tag `vX.Y.Z`, ajouter `./gradlew assembleRelease`, la section `## [X.Y.Z]` du CHANGELOG, et la recette sur téléphone : [docs/RECETTE.md](docs/RECETTE.md) ou [docs/RECETTE-RAPIDE.md](docs/RECETTE-RAPIDE.md).

## Ne jamais committer

- Keystore (`*.jks`, `*.keystore`), mots de passe, jetons, `local.properties`.
- APK et dossiers `build/`.
- Les modèles OCR téléchargés.
