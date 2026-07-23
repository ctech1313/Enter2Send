import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val configuredSigningProperties =
    providers.gradleProperty("enter2sendSigningProperties").orNull
        ?: System.getenv("ENTER2SEND_SIGNING_PROPERTIES")
val releaseSigningPropertiesFile = file(
    configuredSigningProperties
        ?: "${System.getProperty("user.home")}/.android/enter2send-release.properties"
)
val releaseSigningProperties = Properties()
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}
val requiredSigningProperties = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
)
val hasReleaseSigning = requiredSigningProperties.all {
    !releaseSigningProperties.getProperty(it).isNullOrBlank()
}

android {
    namespace = "com.ctech.enter2send"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ctech.enter2send"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dictation"
            versionNameSuffix = "-dictation"
            resValue("string", "app_name", "Enter2Send Dictation")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

gradle.taskGraph.whenReady {
    val releasePackagingTasks = setOf(
        "assembleRelease",
        "bundleRelease",
        "installRelease",
        "packageRelease"
    )
    val releaseRequested = allTasks.any {
        it.name in releasePackagingTasks
    }
    if (releaseRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is not configured. Copy keystore.properties.example " +
                "to the configured external signing-properties path and provide all four values."
        )
    }
}
