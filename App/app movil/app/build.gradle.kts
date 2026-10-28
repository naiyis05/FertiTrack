import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Datos del robot. local.properties (no versionado) tiene prioridad sobre
// gradle.properties; la clave del AP solo debe vivir en local.properties.
val propiedadesLocales = Properties().apply {
    val archivo = rootProject.file("local.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

fun propiedadRobot(nombre: String, porDefecto: String = ""): String =
    propiedadesLocales.getProperty(nombre)
        ?: providers.gradleProperty(nombre).orNull
        ?: porDefecto

fun literalJava(valor: String): String =
    "\"" + valor.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Firma de release: el .jks vive fuera del repositorio y sus datos solo en local.properties.
// Sin ellos, assembleRelease genera un APK sin firmar (no instalable).
val almacenFirma: String? = propiedadesLocales.getProperty("fertitrack.release.storeFile")

android {
    namespace = "com.fertitrack.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fertitrack.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "2.0.0"

        buildConfigField("String", "ROBOT_IP", literalJava(propiedadRobot("fertitrack.robot.ip", "192.168.4.1")))
        buildConfigField("String", "AP_SSID", literalJava(propiedadRobot("fertitrack.ap.ssid", "FertiTrack_AP")))
        buildConfigField("String", "AP_PASSWORD", literalJava(propiedadRobot("fertitrack.ap.password")))
    }

    signingConfigs {
        if (almacenFirma != null) {
            create("release") {
                storeFile = file(almacenFirma)
                storePassword = propiedadesLocales.getProperty("fertitrack.release.storePassword")
                keyAlias = propiedadesLocales.getProperty("fertitrack.release.keyAlias")
                keyPassword = propiedadesLocales.getProperty("fertitrack.release.keyPassword")
            }
        }
    }

    buildTypes {
        // Id propio para que debug (con simulador) y release convivan en el mismo teléfono.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (almacenFirma != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric con SDK 36+ (ApplicationSharedMemory) usa jdk.internal.access en JDK 17+.
        unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vico.compose.m3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
