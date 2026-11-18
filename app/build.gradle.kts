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
        // x86_64 : émulateurs et Chromebook (libarchive, rclone et ONNX Runtime y sont compilés).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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

    // Lint obligatoire (audit M-09) : une erreur arrête la construction, les avertissements non.
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        xmlReport = true
        htmlReport = true
    }

    // Un APK par processeur (plus léger : ONNX Runtime pèse lourd) et un APK universel.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
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
    // third_party/ : copie patchée produite par patch.sh à partir de go.mod et patches/.
    inputs.files(fileTree(src) { include("**/*.go", "go.mod", "go.sum", "build.sh", "patch.sh", "patches/**"); exclude("third_party/**") })
    outputs.file(rcloneAar)
    workingDir = src
    executable("bash")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    argumentProviders.add(CommandLineArgumentProvider { listOf("build.sh", rcloneAar.get().asFile.absolutePath, sdk.get().asFile.absolutePath) })
}
tasks.named("preBuild") { dependsOn(buildRclone) }

// libarchive (BSD-2) réduit aux RAR 4, RAR 5 et ISO 9660, avec son pont JNI : dossier « native »,
// compilé par CMake et le NDK (sources figées et vérifiées par build.sh). Pré-requis : cmake, ninja.
val nativeSrc = rootProject.file("app/native")
val nativeInputs = fileTree(nativeSrc) { include("*.c", "CMakeLists.txt", "build.sh") }
val libarchiveJni = layout.buildDirectory.dir("libarchive/jniLibs")
val buildLibarchive = tasks.register<Exec>("buildLibarchive") {
    inputs.files(nativeInputs)
    outputs.dir(libarchiveJni)
    workingDir = nativeSrc
    executable("bash")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("build.sh", libarchiveJni.get().asFile.absolutePath, sdk.get().asFile.absolutePath, "arm64-v8a", "armeabi-v7a", "x86_64")
    })
}
tasks.named("preBuild") { dependsOn(buildLibarchive) }
// Chemin fixe (AGP refuse un Provider ici) ; la dépendance est assurée par preBuild ci-dessus.
android.sourceSets.getByName("main").jniLibs.srcDir(libarchiveJni.get().asFile)

// Même bibliothèque compilée pour la machine : les tests JVM lisent de vraies archives RAR.
val libarchiveHost = layout.buildDirectory.dir("libarchive/host-out")
val buildLibarchiveHost = tasks.register<Exec>("buildLibarchiveHost") {
    inputs.files(nativeInputs)
    outputs.dir(libarchiveHost)
    workingDir = nativeSrc
    executable("bash")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    argumentProviders.add(CommandLineArgumentProvider { listOf("build.sh", libarchiveHost.get().asFile.absolutePath, sdk.get().asFile.absolutePath, "host") })
}
tasks.withType<Test>().configureEach {
    dependsOn(buildLibarchiveHost)
    systemProperty("xfiles.libarchive", layout.buildDirectory.file("libarchive/host-out/host/libxfarchive.so").get().asFile.absolutePath)
    systemProperty("xfiles.libarchive.tests", rootProject.file("app/native/third_party/libarchive/libarchive/test").absolutePath)
}

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

    // Archives : 7z, tar, gz, bz2, xz (Commons Compress + XZ), zip chiffrés (zip4j) ;
    // RAR 4, RAR 5 et ISO par libarchive (dossier « native », BSD-2 ; junrar retiré, licence non libre).
    implementation(libs.commons.compress)
    implementation(libs.xz)
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

// Licences des dépendances livrées dans l'APK (Lot 3) : XFiles est sous GPL-3.0, aucune dépendance
// ne doit l'en empêcher (licence UnRAR de junrar, licences non libres…). Les licences sont lues dans
// les POM Maven (et leurs parents) ; une licence inconnue ou refusée arrête la construction.
val checkLicenses = tasks.register("checkLicenses") {
    group = "verification"
    description = "Vérifie que les dépendances de l'APK sont compatibles avec la GPL-3.0."
    val runtime = configurations.named("releaseRuntimeClasspath")
    val deps = dependencies
    val report = layout.buildDirectory.file("reports/licences.txt")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        // Modules interdits quelle que soit la licence annoncée.
        val bannedModules = listOf(Regex("(?i)junrar"), Regex("(?i)unrar"))
        // Licences incompatibles avec la GPL-3.0 (ou non libres).
        val denied = listOf(
            "unrar", "proprietary", "commercial", "commons clause", "sspl", "server side public",
            "business source", "busl", "elastic license", "non-commercial", "noncommercial",
            "json license", "good, not evil", "eclipse public license - v 1.0", "epl-1.0",
            "eclipse public license 1.0", "cddl", "common development and distribution",
            "original bsd", "4-clause",
        )
        // Licences compatibles avec la GPL-3.0.
        val allowed = listOf(
            "apache", "mit", "bsd", "0bsd", "isc", "zlib", "public domain", "cc0", "unlicense",
            "gpl", "lgpl", "gnu general public", "gnu lesser", "mozilla public license 2.0", "mpl 2.0", "mpl-2.0",
            "bouncy castle", "eclipse public license - v 2.0", "epl-2.0", "eclipse public license v2.0",
            "eclipse distribution license", "edl", "android software development kit license",
            "the go license",
        ).map { Regex("\\b" + Regex.escape(it) + "\\b") }
        val ids = runtime.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .distinctBy { it.displayName }

        fun pomOf(group: String, module: String, version: String): groovy.util.Node? {
            val id = org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier.newId(
                org.gradle.api.internal.artifacts.DefaultModuleIdentifier.newId(group, module), version,
            )
            // Quelques essais : un dépôt qui ne répond pas ne doit pas faire échouer le contrôle.
            var file: File? = null
            for (attempt in 1..3) {
                val result = deps.createArtifactResolutionQuery()
                    .forComponents(id)
                    .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
                    .execute()
                file = result.resolvedComponents.firstOrNull()
                    ?.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
                    ?.filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()
                    ?.firstOrNull()?.file
                if (file != null) break
                result.components.filterIsInstance<org.gradle.api.artifacts.result.UnresolvedComponentResult>()
                    .forEach { logger.warn("POM $group:$module:$version (essai $attempt) : ${it.failure.message}") }
            }
            if (file == null) return null
            return runCatching { groovy.xml.XmlParser(false, false).parse(file) }
                .onFailure { logger.warn("POM illisible $group:$module:$version : ${it.message}") }.getOrNull()
        }

        fun child(node: groovy.util.Node, name: String): groovy.util.Node? =
            node.children().filterIsInstance<groovy.util.Node>().firstOrNull { it.name().toString().substringAfter('}') == name }

        fun licensesOf(group: String, module: String, version: String, depth: Int = 0): List<String> {
            val pom = pomOf(group, module, version) ?: return emptyList()
            val found = child(pom, "licenses")?.children()?.filterIsInstance<groovy.util.Node>()?.map { lic ->
                listOfNotNull(child(lic, "name")?.text(), child(lic, "url")?.text()).joinToString(" ")
            }.orEmpty()
            if (found.isNotEmpty() || depth >= 6) return found
            val parent = child(pom, "parent") ?: return emptyList()
            val pg = child(parent, "groupId")?.text() ?: return emptyList()
            val pa = child(parent, "artifactId")?.text() ?: return emptyList()
            val pv = child(parent, "version")?.text() ?: return emptyList()
            return licensesOf(pg, pa, pv, depth + 1)
        }

        val lines = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (id in ids.sortedBy { it.displayName }) {
            val name = "${id.group}:${id.module}:${id.version}"
            if (bannedModules.any { it.containsMatchIn(name) }) {
                problems += "$name : module interdit (licence incompatible avec la GPL-3.0)"
                continue
            }
            val licenses = licensesOf(id.group, id.module, id.version)
            lines += "$name : ${licenses.joinToString(" | ").ifEmpty { "?" }}"
            val text = licenses.joinToString(" ").lowercase()
            when {
                licenses.isEmpty() -> problems += "$name : licence introuvable dans le POM"
                denied.any { it in text } && allowed.none { it.containsMatchIn(text) } -> problems += "$name : licence refusée ($text)"
                allowed.none { it.containsMatchIn(text) } -> problems += "$name : licence inconnue ($text), à vérifier"
            }
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText(lines.joinToString("\n") + "\n") }
        if (problems.isNotEmpty()) throw GradleException("Licences à revoir :\n" + problems.joinToString("\n"))
        logger.lifecycle("Licences : ${ids.size} dépendances compatibles avec la GPL-3.0.")
    }
}
