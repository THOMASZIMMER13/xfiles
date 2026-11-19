# Recette rapide avant la 1.0.0 (15 minutes)

Elle vérifie sur un vrai téléphone, avec TalkBack, les points que les audits ont laissés
« à confirmer » ou « plausibles ». Le protocole complet reste [RECETTE.md](RECETTE.md).
Si un test échoue, ne pas publier : noter le numéro du test et ce que TalkBack a dit.

Notation :
- **D** : toucher deux fois.
- **Actions** : menu des actions TalkBack (balayer vers le haut puis vers la droite, ou trois doigts).
- Les guillemets donnent ce que TalkBack doit dire. Un `…` remplace un nom ou une valeur qui varie.

## 0. Préparation (2 min)

- Installer l'APK release signé de la CI, puis activer TalkBack.
- Dans Téléchargements, avoir un fichier d'au moins 1 Go (une vidéo, par exemple) et une photo.
- Créer un coffre qui contient un fichier audio (mp3), puis le fermer.
- Démarrer Shizuku et autoriser XFiles.
- Préparer une clé USB formatée en FAT32 et son adaptateur OTG.
- Dans Réglages, section Explorer, activer « Double panneau ».

## 1. Progression d'une copie (3 min)

**1.1 Copie longue**
- Action : sur le fichier de 1 Go, Actions, puis Copier. Ouvrir Documents, puis D sur « Coller ici ».
- TalkBack dit : « Copie de … vers Documents ». Ensuite, il se tait jusqu'à la fin.
- Placer le focus sur le bandeau du bas : « Copie de … vers Documents, 30 %, … ». Balayer pour sortir du bandeau, puis y revenir.
- À la fin : « … copié dans Documents. »
- Réussi si :
  - aucune annonce spontanée pendant la copie ;
  - le pourcentage lu est toujours un multiple de 10 ;
  - le bilan est lu une seule fois.

**1.2 Annulation**
- Action : relancer la même copie, choisir « Garder les deux », puis D sur « Annuler » dans le bandeau.
- TalkBack dit : « Opération annulée. »
- Réussi si Documents ne contient ni copie partielle ni fichier `.xfiles-remplace-…`.

## 2. Double panneau (2 min)

**2.1 Focus à l'ouverture**
- Action : ouvrir l'onglet Explorer.
- TalkBack dit : « Panneau 1 : … », annoncé comme titre.
- Réussi si un seul élément reçoit le focus, sans double annonce et sans que le focus saute dans le panneau 2.

**2.2 Copier vers l'autre panneau**
- Action :
  1. Dans le panneau 2, ouvrir Musique.
  2. Revenir au panneau 1, sur la photo.
  3. Actions, puis « Copier vers Musique ».
- TalkBack dit : « … copié dans Musique. »
- Réussi si le focus reste sur la photo dans le panneau 1 et que la photo apparaît dans le panneau 2.

**2.3 Retour arrière**
- Action : dans le panneau 2, entrer dans un sous-dossier, puis faire le geste Retour.
- Réussi si seul le panneau 2 remonte et que le focus revient sur ce sous-dossier.

## 3. Coffre : écran verrouillé et confidentialité (3 min)

**3.1 Notification et écran verrouillé**
- Action : ouvrir le coffre, puis D sur le mp3. Verrouiller le téléphone, puis le réveiller sans le déverrouiller.
- TalkBack dit, sur l'écran verrouillé et dans la notification : « Fichier du coffre ».
- Réussi si :
  - le vrai nom du fichier n'est lu nulle part hors d'XFiles (notification, écran verrouillé, montre ou voiture si l'une d'elles est connectée) ;
  - une fois revenu dans XFiles, le mini-lecteur annonce le vrai nom.

**3.2 Applications récentes**
- Action : coffre toujours ouvert, ouvrir l'écran des applications récentes.
- Réussi si l'aperçu d'XFiles est vide ou noir. Ce test compte aussi sous Android 11 et 12.

**3.3 Fermeture du coffre**
- Action : sur la ligne du coffre, Actions, puis « Verrouiller le coffre ». Ouvrir ensuite l'onglet Récents.
- TalkBack : la ligne du coffre indique qu'il est verrouillé.
- Réussi si le mp3 du coffre n'apparaît pas dans Récents.

## 4. Shizuku et Android/data (2 min)

**4.1 État**
- Action : Réglages, Sécurité, puis « Données des applications ».
- TalkBack dit : « Shizuku est actif : les données des applications sont accessibles. »

**4.2 Écriture puis suppression**
- Action :
  1. D sur « Ouvrir les données des applications ».
  2. Ouvrir Android/media, puis le dossier d'une application de messagerie.
  3. Créer le dossier `xfiles-test`, l'ouvrir, puis « Envoyer ici » la photo copiée au préalable.
  4. Revenir en arrière et supprimer `xfiles-test`.
- TalkBack dit :
  - « Dossier xfiles-test créé. » ;
  - un bilan d'envoi ;
  - après la suppression, le focus va au voisin.
- Réussi si `xfiles-test` a disparu et que le reste du dossier de l'application est intact (rouvrir l'application pour vérifier).

## 5. Clé USB OTG (2 min)

**5.1 Branchement**
- Action : brancher la clé, l'Explorer étant ouvert.
- TalkBack dit : « Nouvel emplacement : … ». L'annonce vient seule, sans rien toucher.

**5.2 Limite FAT32** (seulement si un fichier de plus de 4 Go est disponible)
- Action : Copier ce fichier, ouvrir la clé, puis « Coller ici ».
- TalkBack dit : « Impossible de copier … : un fichier dépasse 4 Go, la limite d'une carte ou d'une clé formatée en FAT32. »
- Réussi si le refus arrive avant tout transfert et que rien n'est créé sur la clé.

**5.3 Copie et retrait**
- Action : copier la photo sur la clé, puis débrancher la clé.
- TalkBack dit :
  - « … copié dans … » ;
  - au retrait : « Emplacement retiré : … ».
- Réussi si, en rebranchant la clé sur un ordinateur, la photo est lisible.

## 6. Mise à jour de la Galerie (1 min)

**6.1 Ajout**
- Action : copier la photo dans Images (DCIM ou Pictures), puis ouvrir l'application Galerie ou Google Photos.
- Réussi si la photo apparaît en moins de 10 secondes, sans redémarrer le téléphone.

**6.2 Corbeille**
- Action : dans XFiles, mettre cette copie à la corbeille, puis revenir dans la Galerie.
- TalkBack dit, dans XFiles : « … mis à la corbeille. »
- Réussi si la photo a disparu de la Galerie et qu'aucune vignette vide ne reste.

## Bilan

| Test | Résultat | Remarque |
|------|----------|----------|
| 1.1 à 1.2 | | |
| 2.1 à 2.3 | | |
| 3.1 à 3.3 | | |
| 4.1 à 4.2 | | |
| 5.1 à 5.3 | | |
| 6.1 à 6.2 | | |
