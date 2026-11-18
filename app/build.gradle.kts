plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signature release : fournie par la CI via des variables d'environnement.
// Sans ces variables, seul l'APK debug est produit.
val releaseKeystore: String? = System.getenv("XFILES_KEYSTORE_FILE")

android {
    namespace = "io.github.thomaszimmer13.xfiles"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.thomaszimmer13.xfiles"
        minSdk = 30
        targetSdk = 36
        // La CI fournit un numéro de build croissant ; 1 en local.
        versionCode = System.getenv("XFILES_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("XFILES_VERSION_NAME") ?: "1.0.0-dev"
        // Décodeur FFmpeg : seulement les processeurs des téléphones (ARM). Sur un émulateur x86,
        // le lecteur fonctionne sans FFmpeg.
        // Google Drive : identifiant d'application « bureau » d'XFiles (variables du dépôt). Vide :
        // chaque utilisateur saisit le sien.
        buildConfigField("String", "GDRIVE_CLIENT_ID", "\"${System.getenv("XFILES_GDRIVE_CLIENT_ID").orEmpty()}\"")
        buildConfigField("String", "GDRIVE_CLIENT_SECRET", "\"${System.getenv("XFILES_GDRIVE_CLIENT_SECRET").orEmpty()}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("XFILES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XFILES_KEY_ALIAS")
                keyPassword = System.getenv("XFILES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Retour de connexion propre à chaque version installée (pas de choix entre deux apps).
            manifestPlaceholders["oauthScheme"] = "xfiles-auth-debug"
            buildConfigField("String", "OAUTH_SCHEME", "\"xfiles-auth-debug\"")
        }
        release {
            manifestPlaceholders["oauthScheme"] = "xfiles-auth"
            buildConfigField("String", "OAUTH_SCHEME", "\"xfiles-auth\"")
            // R8 : code et ressources inutilisés retirés, APK plus léger et plus rapide.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Un APK par processeur (plus léger : ONNX Runtime pèse lourd) et un APK universel.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        // Bibliothèques natives compressées dans l'APK : téléchargement deux fois plus léger.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        // Données de cryptographie post-quantique de Bouncy Castle (dépendance de pdfbox), inutiles ici.
        resources.excludes += "org/bouncycastle/pqc/**"
    }
}

// Pont rclone (Google Drive, OneDrive, Dropbox…) : module Go du dossier « rclone », compilé en
// .aar par gomobile. Pré-requis : Go et le NDK Android.
val rcloneAar = layout.buildDirectory.file("rclone/rclone.aar")
val buildRclone = tasks.register<Exec>("buildRclone") {
    val src = rootProject.file("rclone")
    inputs.files(fileTree(src) { include("**/*.go", "go.mod", "go.sum", "build.sh") })
    outputs.file(rcloneAar)
    workingDir = src
    executable("bash")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    argumentProviders.add(CommandLineArgumentProvider { listOf("build.sh", rcloneAar.get().asFile.absolutePath, sdk.get().asFile.absolutePath) })
}
tasks.named("preBuild") { dependsOn(buildRclone) }

dependencies {
    implementation(files(rcloneAar).builtBy(buildRclone))
    implementation(libs.androidx.core.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Archives : 7z, tar, gz, bz2, xz (Commons Compress + XZ), RAR 4 (junrar), zip chiffrés (zip4j).
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.zip4j)

    // Lecteur audio et vidéo
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ffmpeg.decoder)

    // Lecteur de documents
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android) {
        // Ancien Bouncy Castle (jdk15to18) remplacé par celui de sshj et smbj (jdk18on), même code.
        exclude(group = "org.bouncycastle")
    }
    implementation(libs.bcprov)
    implementation(libs.bcpkix)

    // Serveurs : SMB 2/3, SFTP, FTP/FTPS, WebDAV.
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.commons.net)
    implementation(libs.okhttp)

    // Comptes en ligne : page de connexion dans un onglet du navigateur.
    implementation(libs.androidx.browser)

    // Reconnaissance de texte : ONNX Runtime (MIT) fait tourner les modèles PaddleOCR (Apache 2.0).
    implementation(libs.onnxruntime.android)

    // Scanner de documents : caméra (CameraX).
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // Tests du moteur de reconnaissance sur la JVM : même API, bibliothèque native pour PC.
    testImplementation(libs.onnxruntime.jvm)
    // Tests d'accessibilité de l'interface sur la JVM (Robolectric) : structure lue par TalkBack.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Les tests JVM utilisent ONNX Runtime pour PC, pas la version Android (bibliothèques natives ARM).
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
