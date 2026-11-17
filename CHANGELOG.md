# Journal des versions

Les versions suivent la numérotation `majeure.mineure.correctif`.

## [1.0.0]

Première version publique : un gestionnaire de fichiers pensé d'abord pour TalkBack, dans l'esprit de l'app Fichiers d'iOS.

### Parcourir
- Trois onglets en bas : Récents, Favoris, Explorer. Toucher l'onglet actif ramène à sa racine.
- Explorer : mémoire interne, carte SD et clé USB (annoncées quand on les branche ou les retire), corbeille, dossiers favoris.
- Chaque fichier ou dossier est un seul élément TalkBack : nom, type, taille, date.
- Fil d'Ariane avec des actions pour remonter directement vers un dossier parent.
- Tri par nom, date, taille ou type ; fichiers cachés affichables.

### Agir sur les fichiers
- Toutes les actions sont des actions personnalisées TalkBack : ouvrir avec, partager, renommer, copier, déplacer, compresser, favori, sélectionner, détails, mettre à la corbeille.
- Créer un dossier, renommer (l'extension n'est pas sélectionnée), avec des erreurs lues clairement.
- Sélection multiple : chaque ligne devient une case à cocher, le total est annoncé.
- Copier ou déplacer, puis « Coller ici » ou « Déplacer ici » dans le dossier de destination ; conflits de nom gérés (remplacer, garder les deux, ignorer, appliquer à tous).
- Corbeille par volume, restauration à l'emplacement d'origine, purge après 30 jours ; suppression définitive possible avec confirmation.
- Les opérations longues continuent en arrière-plan, avec notification de progression et bouton Annuler.

### Rechercher et retrouver
- Recherche par nom dans un dossier ou partout, sans tenir compte des majuscules ni des accents.
- Récents : fichiers ouverts depuis XFiles et fichiers modifiés ces 7 derniers jours.
- Favoris : dossiers et fichiers.

### Archives
- Parcourir une archive comme un dossier, ouvrir un fichier qu'elle contient : zip, 7z, tar, tar.gz, tar.bz2, tar.xz, gz, bz2, xz, RAR (jusqu'à la version 4).
- Archives protégées : le mot de passe est demandé, avec une case « Afficher le mot de passe » ; un mot de passe faux est annoncé.
- Extraire tout ou partie, compresser un élément ou une sélection en zip (mot de passe AES-256 facultatif), 7z ou tar.gz, avec le nom de son choix.

### Partager et échanger avec les autres apps
- « Enregistrer dans XFiles » depuis le menu Partager de n'importe quelle app : choisir le dossier, puis « Enregistrer ici ».
- XFiles peut servir de sélecteur de fichier (« Joindre un fichier ») : ouvrir un fichier le renvoie à l'app qui l'a demandé.
- Raccourci d'un dossier sur l'écran d'accueil.

### Catégories et nettoyage
- Racine de l'Explorer : Téléchargements, Images, Vidéos, Audio, Documents, Archives, Applications (APK), avec leur nombre de fichiers.
- Nettoyage et analyse : gros fichiers, doublons (contenu vérifié par empreinte SHA-256), anciens téléchargements, dossiers les plus lourds. Listes à cocher, « Garder le plus ancien de chaque groupe », espace libéré annoncé.
- Détails : taille totale d'un dossier, empreintes MD5 et SHA-256 d'un fichier, copiables.
- Dupliquer un fichier ou un dossier.
- Récents : dossiers parcourus récemment. Favoris : « Monter » et « Descendre » pour les réordonner.

### Réglages
- Confirmation avant la corbeille (désactivable), fichiers cachés, informations sur la version et la licence.
- Annoncer la position dans les listes (« 3 sur 12 »), sons de confirmation discrets (réussite, échec), tous deux désactivables.
