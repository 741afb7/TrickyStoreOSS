plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.a13e300.stub"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
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

    lint {
        checkReleaseBuilds = false
        abortOnError = true
    }
}

dependencies {
    compileOnly(libs.annotation)
    compileOnly(libs.dev.rikka.hidden.stub)
}