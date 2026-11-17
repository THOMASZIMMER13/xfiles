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
        }
        release {
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
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
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

    testImplementation(libs.junit)
    // Tests d'accessibilité de l'interface sur la JVM (Robolectric) : structure lue par TalkBack.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
