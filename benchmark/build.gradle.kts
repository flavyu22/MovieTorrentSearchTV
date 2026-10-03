plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "io.github.flavyu22.movietorrentsearchtv.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            // This key signs only the device-test artifact and its release-like target.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        animationsDisabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
}

