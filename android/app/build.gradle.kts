import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Chave de upload da Play Store: android/keystore.properties (fora do git), com storeFile, storePassword,
// keyAlias e keyPassword. Sem o arquivo, o release é assinado com a chave de debug (só para testar localmente).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "br.com.contaslt"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.contaslt"
        minSdk = 26
        targetSdk = 37
        // Cada envio à Play Store precisa de um versionCode maior: ./gradlew bundleRelease -PversionCode=2 -PversionName=1.1
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
        // Servidor padrão: o backend no Railway. Trocável na tela de login (ex.: http://10.0.2.2:8080 no emulador).
        buildConfigField("String", "DEFAULT_SERVER", "\"https://contaslt-production.up.railway.app\"")
        buildConfigField("String", "PRIVACY_URL", "\"https://contaslt.web.app/privacidade\"")
        // Campo "Servidor" no login: só em debug/staging (no app da loja fica escondido).
        buildConfigField("boolean", "SHOW_SERVER_FIELD", "false")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
        debug {
            buildConfigField("boolean", "SHOW_SERVER_FIELD", "true")
        }
        // Igual ao release (R8 ligado), mas aceita o backend local por HTTP: para testar o build otimizado.
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".staging"
            buildConfigField("boolean", "SHOW_SERVER_FIELD", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.fragment)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.recyclerview)
    implementation(libs.swiperefresh)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
}
