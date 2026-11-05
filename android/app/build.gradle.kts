plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "br.com.contaslt"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.contaslt"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        // Servidor padrão: o backend no Railway. Trocável na tela de login (ex.: http://10.0.2.2:8080 no emulador).
        buildConfigField("String", "DEFAULT_SERVER", "\"https://contaslt-production.up.railway.app\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
