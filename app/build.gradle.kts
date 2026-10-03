import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

private fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun protectedConfig(name: String) = providers.gradleProperty(name)
    .orElse(providers.environmentVariable(name))
    .orElse(localProperties.getProperty(name).orEmpty())
    .map { value ->
        value.trim().takeUnless {
            it.equals("replace_with_your_tmdb_key", ignoreCase = true) ||
                it.startsWith("CHANGE_ME", ignoreCase = true)
        }.orEmpty()
    }

val tmdbApiKey = protectedConfig("TMDB_API_KEY")
val updateManifestUrl = protectedConfig("UPDATE_MANIFEST_URL")
val updateRepository = protectedConfig("UPDATE_REPOSITORY")
// Optional: enables the Rutracker Russian-language provider. Empty means "not configured"
// and the provider reports an explicit error instead of silently returning no results.
val rutrackerApiKey = protectedConfig("RUTRACKER_API_KEY")

val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val appVersionName = "2.1.0"
val appVersionCode = 10

fun isValidUpdateManifestUrl(value: String): Boolean {
    if (value.length !in 1..2_048 || value.any { it.isWhitespace() || it.isISOControl() }) {
        return false
    }
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host ?: return false
    val dnsHost = Regex(
        "(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+" +
            "[A-Za-z]{2,63}",
    )
    val pathSegment = Regex("[A-Za-z0-9._~-]+")
    val segments = uri.rawPath.removePrefix("/").split('/')
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.isOpaque &&
        dnsHost.matches(host) &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        uri.port in listOf(-1, 443) &&
        segments.isNotEmpty() &&
        segments.all { pathSegment.matches(it) && it != "." && it != ".." } &&
        segments.last() == "update.json"
}

android {
    namespace = "io.github.flavyu22.movietorrentsearchtv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.flavyu22.movietorrentsearchtv"
        minSdk = 24
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TMDB_API_KEY", tmdbApiKey.get().asBuildConfigString())
        buildConfigField("String", "UPDATE_MANIFEST_URL", updateManifestUrl.get().asBuildConfigString())
        buildConfigField("String", "UPDATE_REPOSITORY", updateRepository.get().asBuildConfigString())
        buildConfigField("String", "RUTRACKER_API_KEY", rutrackerApiKey.get().asBuildConfigString())
        buildConfigField("boolean", "IS_BENCHMARK", "false")

        vectorDrawables.useSupportLibrary = true

        // Strip library string translations the app itself never offers. All user-facing
        // app text lives in AppStrings for exactly these locales (see Translations and
        // res/xml/locales_config.xml), so every other library locale is dead weight in
        // the release APK. Unsupported devices fall back to the default (English) resources.
        androidResources.localeFilters += setOf("en", "ro", "it", "es", "fr", "de", "pt", "ru", "el")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_SELF_UPDATE", "false")
            buildConfigField("boolean", "ALLOW_LAN_CLEARTEXT", "false")
        }
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_SELF_UPDATE", "true")
            buildConfigField("boolean", "ALLOW_LAN_CLEARTEXT", "true")
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            buildConfigField("boolean", "IS_BENCHMARK", "true")
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

    androidResources.generateLocaleConfig = false

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

tasks.register("tmdbConfigurationStatus") {
    group = "verification"
    description = "Reports whether TMDB_API_KEY is available without printing the secret."
    doLast {
        val configured = tmdbApiKey.get().isNotBlank()
        logger.lifecycle("TMDB_API_KEY configured: $configured")
        if (!configured) {
            logger.lifecycle("Add TMDB_API_KEY=<v3 key> to local.properties or the environment.")
        }
    }
}

tasks.register("validateProductionConfiguration") {
    group = "verification"
    description = "Validates common production secrets and signing configuration."
    doLast {
        check(tmdbApiKey.get().isNotBlank()) {
            "TMDB_API_KEY must be supplied for a production publication."
        }
        check(releaseSigningConfigured && file(requireNotNull(releaseStoreFile)).isFile) {
            "All Android signing variables must point to a readable release keystore."
        }
    }
}

tasks.register("validateDirectProductionConfiguration") {
    group = "verification"
    description = "Validates the direct-distribution update channel."
    dependsOn("validateProductionConfiguration")
    doLast {
        check(isValidUpdateManifestUrl(updateManifestUrl.get())) {
            "UPDATE_MANIFEST_URL must match the runtime HTTPS update.json policy."
        }
        check(
            updateRepository.get().matches(
                Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/" +
                    "[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})"),
            ),
        ) { "UPDATE_REPOSITORY must use the owner/repository form." }
        check(providers.environmentVariable("RELEASE_TAG").orNull == "v$appVersionName") {
            "RELEASE_TAG must exactly match v$appVersionName."
        }
    }
}

tasks.register("generateUpdateManifest") {
    group = "distribution"
    description = "Generates the verified update manifest for the direct release."
    dependsOn("assembleDirectRelease", "validateDirectProductionConfiguration")
    val releaseApk = layout.buildDirectory.file(
        "outputs/apk/direct/release/app-direct-release.apk",
    )
    val outputFile = layout.buildDirectory.file("release/update.json")
    inputs.property("appVersionCode", appVersionCode)
    inputs.property("appVersionName", appVersionName)
    inputs.property("updateRepository", updateRepository)
    inputs.property("releaseTag", providers.environmentVariable("RELEASE_TAG").orElse(""))
    inputs.file(releaseApk)
    outputs.file(outputFile)
    doLast {
        val repository = updateRepository.get()
        val releaseTag = providers.environmentVariable("RELEASE_TAG").orNull
        val apk = releaseApk.get().asFile
        check(apk.isFile) { "Direct release APK was not produced: ${apk.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val sha256 = apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """{
                  "versionCode": $appVersionCode,
                  "versionName": "$appVersionName",
                  "apkUrl": "https://github.com/$repository/releases/download/$releaseTag/app-direct-release.apk",
                  "sha256": "$sha256",
                  "sizeBytes": ${apk.length()},
                  "releaseNotes": "See the GitHub release notes."
                }
                """.trimIndent(),
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil)
    implementation(libs.shimmer.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okio)

    testImplementation(libs.junit)
    // Real org.json so local JVM unit tests can exercise the production JSON parsers.
    testImplementation(libs.org.json)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
