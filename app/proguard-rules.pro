# Règles R8 de XFiles.
# Compose, AndroidX et DataStore fournissent leurs propres règles ; le code de l'app n'utilise pas la réflexion.
# Les composants déclarés dans le manifeste (activité, service, FileProvider) sont conservés automatiquement.

# Garder des traces de plantage lisibles (numéros de ligne), sans exposer les noms de fichiers source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# pdfbox-android : décodeur JPEG 2000 optionnel, absent (les images ne servent pas à la lecture du texte).
-dontwarn com.gemalto.jp2.**
# pdfbox charge polices et tables par réflexion et par ressources : on le garde tel quel.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# ONNX Runtime : la bibliothèque native appelle les classes Java par leur nom (JNI).
-keep class ai.onnxruntime.** { *; }

# Serveurs réseau. Kerberos (GSS) et expressions EL de mbassador : non utilisés, absents d'Android.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn javax.security.auth.**
-dontwarn sun.security.**
# smbj (bus d'événements mbassador par annotations), sshj et Bouncy Castle (algorithmes chargés par leur nom).
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class net.schmizz.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.slf4j.**

# Pont rclone (gomobile) : classes appelées depuis le code natif Go.
-keep class go.** { *; }
-keep class io.github.thomaszimmer13.xfiles.rclone.** { *; }

# Shizuku : le service de fichiers est créé par son nom dans le processus de Shizuku.
-keep class io.github.thomaszimmer13.xfiles.data.shizuku.FileService { <init>(); *; }
-keep class io.github.thomaszimmer13.xfiles.data.shizuku.IFileService** { *; }
