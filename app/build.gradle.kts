import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets live only in local.properties (gitignored), never in the source tree.
// Missing values become empty strings; callers treat "" as "not configured" and
// degrade gracefully (STT off, upload disabled) rather than crashing.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// local.properties wins; falling back to an env var lets a build pass a
// secret through the process environment for one invocation (e.g. a CI job
// or `GITHUB_TOKEN=$(gh auth token) ./gradlew ...`) without ever writing it
// to disk. Same env var names the vv-transcribe script already uses.
fun localProperty(key: String, envFallback: String? = null): String {
    val fromFile = localProperties.getProperty(key, "")
    if (fromFile.isNotBlank()) return fromFile
    return envFallback?.let { System.getenv(it) } ?: ""
}

android {
    namespace = "com.montauk.voicecapture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.montauk.voicecapture"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ASSEMBLYAI_API_KEY", "\"${localProperty("assemblyai.apiKey", "ASSEMBLYAI_API_KEY")}\"")
        buildConfigField("String", "GITHUB_TOKEN", "\"${localProperty("github.token", "GITHUB_TOKEN")}\"")
        buildConfigField("String", "VAULT_OWNER", "\"${localProperty("vault.owner").ifBlank { "dobromirmontauk" }}\"")
        buildConfigField("String", "VAULT_REPO", "\"${localProperty("vault.repo").ifBlank { "voice-vault" }}\"")
        // GitHub OAuth App client id for the login screen's device flow (bead
        // vn-edu.14). Not configured out of the box -- set `github.oauthClientId`
        // in local.properties (register a free OAuth App at
        // https://github.com/settings/developers, device flow enabled, no
        // callback URL needed) to make "Sign in with GitHub" actually complete.
        // Until then the button still runs the real device-flow HTTP calls
        // against this placeholder id, which GitHub predictably rejects --
        // the login screen surfaces that as a normal error state, and "Use an
        // access token" keeps working regardless.
        buildConfigField(
            "String",
            "GITHUB_OAUTH_CLIENT_ID",
            "\"${localProperty("github.oauthClientId").ifBlank { "REPLACE_WITH_GITHUB_OAUTH_CLIENT_ID" }}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AssemblyAiStreamingSttClient logs via android.util.Log on its failure
    // paths; plain JVM unit tests otherwise crash with "Method w in
    // android.util.Log not mocked" the moment a real socket hiccups (which
    // the STT integration harness needs to tolerate, not choke on).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Kotlin sources live under src/main/kotlin instead of src/main/java.
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
            // WAV/text fixtures for the STT integration harness (bead
            // vn-edu.21) -- kept under integrationTest/ rather than
            // test/resources so they read as fixtures, not ordinary test data.
            resources.srcDirs("src/integrationTest/resources")
        }
    }
}

// STT integration harness (bead vn-edu.21): AssemblyAiLiveStreamingTest
// streams synthesized audio through the real AssemblyAiStreamingSttClient to
// AssemblyAI's live endpoint -- real network, ~$0.01/run. Excluded from the
// default `test` task; run it explicitly via `./gradlew integrationTest`.
// See docs/stt-harness.md.
tasks.withType<Test>().configureEach {
    if (name != "integrationTest") {
        exclude("**/stt/integration/**")
    }
}

afterEvaluate {
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs the mic-free AssemblyAI streaming STT integration test (real network, ~\$0.01/run). See docs/stt-harness.md."
        val debugUnitTest = tasks.named<Test>("testDebugUnitTest").get()
        testClassesDirs = debugUnitTest.testClassesDirs
        classpath = debugUnitTest.classpath
        include("**/stt/integration/**")
        outputs.upToDateWhen { false }
        testLogging {
            events("passed", "skipped", "failed", "standard_out")
            showStandardStreams = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
