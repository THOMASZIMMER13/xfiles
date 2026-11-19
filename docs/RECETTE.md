# Recette sur téléphone réel

Protocole à dérouler avant chaque version publiée (tag `v*`), avec TalkBack activé, sur l'APK
release signé produit par la CI. Chaque ligne se coche ; un échec bloque la version.

Appareils conseillés : un téléphone récent (Android 14 ou plus, arm64) et, si possible, un plus
ancien (Android 11, arm64 ou armv7). Réglages TalkBack : verbosité par défaut, indications activées.

Notation : **D** = toucher deux fois, **G/D** = balayer à gauche/à droite,
**Actions** = menu des actions personnalisées (balayer haut puis droite, ou trois doigts).

## 1. Installation et premier lancement

- [ ] L'APK s'installe par-dessus la version précédente sans perte des réglages ni des favoris.
- [ ] Au premier lancement, la demande « Accès à tous les fichiers » est annoncée clairement ; la refuser puis l'accepter fonctionne.
- [ ] Le premier élément lu est le titre de l'écran, annoncé comme titre.

## 2. Explorer et lignes de fichiers

- [ ] Chaque fichier est un seul arrêt TalkBack, lu dans l'ordre : nom, type, taille, date.
- [ ] Un dossier est lu : nom, « dossier », nombre d'éléments, date.
- [ ] Aucune ligne n'annonce « 0 octet » pour un fichier de taille inconnue, ni « 1970 ».
- [ ] Les actions d'un fichier : Ouvrir avec, Partager, Renommer, Copier, Déplacer, Dupliquer, Compresser, Favori, Sélectionner, Détails, Mettre à la corbeille.
- [ ] Mettre un fichier à la corbeille : le focus va au fichier suivant (au précédent pour le dernier, au titre si le dossier est vide).
- [ ] Renommer : le focus revient sur l'élément renommé ; l'extension n'est pas sélectionnée.
- [ ] Retour arrière depuis un sous-dossier : le focus revient sur ce sous-dossier.
- [ ] Fil d'Ariane : ses actions remontent directement à un dossier parent.

## 3. Sélection, copie, conflits

- [ ] Sélectionner : les lignes deviennent des cases à cocher, le total est annoncé.
- [ ] Copier puis « Coller ici » : la progression ne bavarde pas (paliers de 10 % seulement en y plaçant le focus), le bilan est annoncé à la fin.
- [ ] Conflit de nom : titre lu comme titre ; Remplacer, Garder les deux, Ignorer, Annuler le collage accessibles ; « Appliquer à tous » est une case à cocher.
- [ ] Annuler une copie longue : l'original est intact, aucun fichier `.xfiles-remplace-…` ne reste.
- [ ] Copier un fichier de plus de 4 Go vers une carte SD ou une clé en FAT32 : refus annoncé avant de commencer.
- [ ] Copier plus que la place libre : « Pas assez d'espace libre » annoncé avant de commencer.
- [ ] Dossier de 10 000 fichiers ou plus (DCIM/Camera) : ouverture et changement de tri sans blocage de TalkBack.

## 4. Archives

- [ ] Parcourir un zip, un 7z, un RAR 4, un RAR 5 et une image ISO comme des dossiers.
- [ ] Extraire tout et un seul élément de chacun.
- [ ] Un RAR chiffré annonce clairement qu'il ne peut pas être extrait.
- [ ] Zip protégé : le mot de passe est demandé une fois, puis oublié après avoir quitté l'archive.
- [ ] Archive très compressée (bombe de test, ratio > 100, plus de 1 Gio) : une confirmation est demandée ; « Annuler » ne laisse rien.
- [ ] Extraction sur un stockage presque plein : message « espace insuffisant », rien de partiel.

## 5. Serveurs, comptes et coffres

- [ ] SFTP : empreinte à accepter la première fois ; changement d'empreinte signalé comme danger.
- [ ] FTPS avec certificat auto-signé : confiance demandée, puis mémorisée.
- [ ] Ligne distante : Télécharger, Partager (fichier), Renommer, Détails, Copier le chemin, Supprimer du serveur.
- [ ] Après suppression distante, le focus va au voisin ; au retour d'un sous-dossier distant, sur ce sous-dossier.
- [ ] Détails d'un élément distant : pas de taille ni de date inventées.
- [ ] Google Drive, OneDrive, Dropbox : connexion dans le navigateur, retour dans XFiles, listage.
- [ ] Coffre : mauvais mot de passe annoncé comme tel ; serveur injoignable annoncé comme panne, pas comme mauvais mot de passe.
- [ ] Coffre ouvert : l'aperçu de l'app est masqué dans les applications récentes.

- [ ] WebDAV en HTTP vers un NAS du réseau local (192.168.x.x) : listage correct ; vers une adresse Internet : refus expliqué.
- [ ] Serveur WebDAV renvoyant des adresses complètes (IIS, certains NAS) : listage correct.
- [ ] Média lu depuis un coffre : la notification et l'écran verrouillé affichent « Fichier du coffre », l'app annonce le vrai nom.
- [ ] Fermer les coffres : le fichier ouvert depuis un coffre n'apparaît pas dans les Récents.

## 6. Double panneau et onglets

- [ ] Double panneau : à l'ouverture, un seul élément reçoit le focus, dans le panneau actif.
- [ ] Les onglets sont lus « nom, onglet, sélectionné, 2 sur 3 » ; « Fermer l'onglet » n'existe que s'il y en a plusieurs.
- [ ] « Copier vers l'autre panneau » et « Déplacer vers l'autre panneau » fonctionnent.

## 7. Recherche, favoris, corbeille, récents

- [ ] Recherche : résultats lus avec leur dossier ; actions Afficher dans le dossier, Ouvrir avec, Partager.
- [ ] La recherche ne boucle pas sur un dossier contenant un lien symbolique vers son parent.
- [ ] Favoris : Monter, Descendre, Retirer ; le focus suit l'élément déplacé.
- [ ] Corbeille : Restaurer, Supprimer définitivement ; un élément revient à son emplacement d'origine.

## 8. Lecteurs

- [ ] Audio : lecture écran éteint, notification, casque ; le titre d'un média de coffre n'apparaît pas dans une app tierce de contrôle.
- [ ] Vidéo : commandes toujours visibles avec TalkBack, piste d'audiodescription signalée.
- [ ] Documents : un paragraphe par arrêt, navigation par titres, « Page 3 sur 12 ».

## 9. Sécurité de l'app

- [ ] Verrouillage XFiles : écran « XFiles est verrouillé » lu comme titre, bouton Déverrouiller.
- [ ] Sans verrouillage d'écran sur le téléphone : « Désactiver le verrouillage et ouvrir » proposé.
- [ ] Ajouter une empreinte au téléphone : l'empreinte d'un coffre est redemandée une fois, sans erreur.

## 10. Bilan

| Appareil | Android | APK | Date | Résultat | Remarques |
|----------|---------|-----|------|----------|-----------|
|          |         |     |      |          |           |
