plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.tuapp.ecg"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tuapp.ecg"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/DEPENDENCIES",
            "META-INF/NOTICE*"
        )
    }

    lint {
        abortOnError = false  // <- esto permitirá compilar aunque haya errores de lint
        checkReleaseBuilds = false
    }
}

/* 🔧 ESTE BLOQUE VA FUERA DE "dependencies" */
configurations.all {
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "support-core-utils")
    exclude(group = "com.android.support", module = "support-fragment")
    exclude(group = "com.android.support", module = "support-v4")
}

dependencies {
    // Librerías AndroidX modernas
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.android.material:material:1.12.0")

    // Librería GraphView para ECG
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
