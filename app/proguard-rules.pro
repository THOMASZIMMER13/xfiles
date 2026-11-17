# Règles R8 de XFiles.
# Compose, AndroidX et DataStore fournissent leurs propres règles ; le code de l'app n'utilise pas la réflexion.
# Les composants déclarés dans le manifeste (activité, service, FileProvider) sont conservés automatiquement.

# Garder des traces de plantage lisibles (numéros de ligne), sans exposer les noms de fichiers source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# junrar journalise via slf4j sans fournir d'implémentation : rien à journaliser dans l'app.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# pdfbox-android : décodeur JPEG 2000 optionnel, absent (les images ne servent pas à la lecture du texte).
-dontwarn com.gemalto.jp2.**
# pdfbox charge polices et tables par réflexion et par ressources : on le garde tel quel.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
