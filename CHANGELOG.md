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

### Lecteur audio et vidéo
- Lecteur intégré, pensé pour TalkBack : position en curseur (glisser haut ou bas : 10 secondes), « Aller à… », temps jamais relu en boucle.
- Lecture en arrière-plan, notification, écran verrouillé, casque et Bluetooth ; pause quand le casque est débranché.
- File du dossier, lecture aléatoire, répétition, vitesse de 0,5 à 3 fois, minuteur de sommeil avec fondu, reprise de position des longs fichiers.
- Listes de lecture m3u, m3u8 et pls (fichiers locaux et webradios), enregistrement de la file en m3u8, « Ajouter à une liste de lecture ».
- Mini-lecteur au-dessus des onglets.
- Vidéo : commandes toujours affichées avec TalkBack, choix de la piste audio (audiodescription signalée), sous-titres intégrés ou posés à côté (srt, vtt, ass), lecture des sous-titres par TalkBack en option, image dans l'image.
- Décodeur FFmpeg pour les formats que le téléphone ne lit pas (AC3, E-AC3, DTS, TrueHD…).

### Documents
- Lecteur intégré pour les textes (txt, code, Markdown, CSV), PDF avec texte, Word (docx), LibreOffice (odt), livres epub et pages web.
- Un élément TalkBack par paragraphe ; titres navigables par titres ; début de page des PDF annoncé « Page 3 sur 12 » ; tableaux lus « Nom : Bob, Ville : Lyon ».
- Liens, numéros de téléphone, courriels et adresses postales repérés : actions « Appeler », « Écrire à », « Ouvrir le lien », « Ouvrir l'adresse dans les cartes » ; « Copier le paragraphe ».
- Recherche dans le document (« Résultat 2 sur 7 »), sommaire, aller à une page, trois tailles de texte, reprise de la lecture là où on l'avait laissée.
- PDF protégés par mot de passe ; PDF scannés signalés, avec « Ouvrir avec ».
- Éditeur de texte (jusqu'à 2 Mo) : vrai champ de saisie Android, encodage et fins de ligne conservés, enregistrement sans risque, avertissement en quittant sans enregistrer. Bouton « Nouveau fichier texte » dans chaque dossier.

### Reconnaissance de texte
- Photos, captures d'écran et PDF scannés : « Reconnaître le texte » (action sur le fichier, ou bouton du lecteur quand un PDF n'a pas de texte).
- Moteur libre PaddleOCR PP-OCRv5 (latin : français, accents, ponctuation), entièrement sur le téléphone ; modèles de 13 Mo téléchargés une seule fois, avec accord, et vérifiés.
- Page penchée, tournée ou à l'envers : redressée automatiquement. Lignes recollées en paragraphes, césures supprimées.
- Résultat dans le lecteur de documents (recherche, liens, numéros, copier) ; on lit la première page pendant que la suite est reconnue ; « Enregistrer le texte » crée un .txt à côté ; résultat gardé en cache.
- Réglages : état des modèles, téléchargement, suppression.

### Scanner de documents
- Bouton « Créer » des dossiers (regroupe Nouveau dossier, Nouveau fichier texte et Scanner un document).
- Guidage vocal pendant le cadrage, d'après le texte vu par la caméra : « Texte coupé en haut », « Trop près », « Trop loin », « Page bien cadrée, ne bougez plus » ; annonce seulement quand le conseil change.
- Photo automatique quand la page est bien cadrée et immobile (désactivable), bouton « Prendre la photo », lampe.
- Plusieurs pages ; chaque page est reconnue aussitôt et son début annoncé ; « Supprimer la dernière page ».
- « Terminer » : PDF cherchable (photo + texte invisible) enregistré dans le dossier, ouvert aussitôt dans le lecteur.

### Réglages
- Confirmation avant la corbeille (désactivable), fichiers cachés, informations sur la version et la licence.
- Annoncer la position dans les listes (« 3 sur 12 »), sons de confirmation discrets (réussite, échec), tous deux désactivables.
- Lecteur : lecteur intégré ou habituel, durées de recul et d'avance, lecture des sous-titres.
- Documents : lecteur de documents intégré ou application habituelle.
