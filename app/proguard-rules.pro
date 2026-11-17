# Règles R8 de XFiles.
# Compose, AndroidX et DataStore fournissent leurs propres règles ; le code de l'app n'utilise pas la réflexion.
# Les composants déclarés dans le manifeste (activité, service, FileProvider) sont conservés automatiquement.

# Garder des traces de plantage lisibles (numéros de ligne), sans exposer les noms de fichiers source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
