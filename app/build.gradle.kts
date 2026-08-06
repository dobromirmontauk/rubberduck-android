import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
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
        // Live tags v2 (bead vn-edu.38): same secret pattern as
        // assemblyai.apiKey above. Blank means "not configured" --
        // TagScorerFactory falls back to the keyless HeuristicTagScorer
        // rather than the app failing to build or crashing at runtime.
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${localProperty("anthropic.apiKey", "ANTHROPIC_API_KEY")}\"")
        buildConfigField("String", "GITHUB_TOKEN", "\"${localProperty("github.token", "GITHUB_TOKEN")}\"")
        buildConfigField("String", "VAULT_OWNER", "\"${localProperty("vault.owner").ifBlank { "dobromirmontauk" }}\"")
        buildConfigField("String", "VAULT_REPO", "\"${localProperty("vault.repo").ifBlank { "voice-vault" }}\"")
        // GitHub OAuth App client id for the login screen's device flow (bead
        // vn-edu.14). Not configured out of the box -- set `github.oauthClientId`
        // in local.properties (register a free OAuth App at
        // https://github.com/settings/developers, device flow enabled, no
        // callback URL needed) to make "Sign in with GitHub" actually complete.
        // Until then, VoiceCaptureApp.isGithubOAuthConfigured() (bead vn-edu.28)
        // detects this placeholder and keeps the login screen from ever running
        // the device flow against it -- "Sign in with GitHub" is demoted to a
        // disabled "soon" button and "Use an access token" (which keeps working
        // regardless) is promoted to primary.
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
        // Robolectric (vn-edu.35) needs the merged manifest + app
        // resources on the unit-test classpath to inflate the real theme,
        // strings, and the ".VoiceCaptureApp" Application declared in
        // AndroidManifest.xml.
        unitTests.isIncludeAndroidResources = true
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

// Roborazzi golden PNGs (bead vn-edu.34) live under source control, not
// build/ -- committed goldens are the whole point (reviewable diffs on
// deliberate visual changes). compare-diff overlays stay under the default
// build/ location since those are throwaway debugging artifacts from a
// failed verify, not something to commit.
roborazzi {
    outputDir.set(file("src/test/screenshot/goldens"))
}

// STT integration harness (bead vn-edu.21): AssemblyAiLiveStreamingTest
// streams synthesized audio through the real AssemblyAiStreamingSttClient to
// AssemblyAI's live endpoint -- real network, ~$0.01/run. Excluded from the
// default `test` task; run it explicitly via `./gradlew integrationTest`.
// See docs/stt-harness.md.
// Roborazzi golden-screenshot tests (bead vn-edu.34, `**/screenshot/**`) are
// excluded from a plain `./gradlew test` run: captureRoboImage() with no
// roborazzi.test.{record,verify,compare} mode active just (re)writes the PNG
// unconditionally, which would silently rewrite committed goldens (and touch
// the working tree) on every ordinary test run instead of asserting anything.
// The Roborazzi Gradle plugin's `recordRoborazziDebug` / `verifyRoborazziDebug`
// / `compareRoborazziDebug` tasks depend on this same `testDebugUnitTest` task
// (confirmed via `./gradlew help --task verifyRoborazziDebug`) rather than
// defining a separate one, and set the record/verify/compare mode only via a
// system property on the *forked test JVM* -- invisible to this build script
// at configuration time. Checking the originally-requested task names instead
// is what actually distinguishes "./gradlew test" from
// "./gradlew verifyRoborazziDebug" here. See README's "Screenshot tests
// (Roborazzi)" section.
val roborazziTaskRequested = gradle.startParameter.taskNames.any { it.contains("Roborazzi") }
tasks.withType<Test>().configureEach {
    if (name != "integrationTest") {
        exclude("**/stt/integration/**")
    }
    // Compose interaction tests (vn-edu.35, AppNavHostInteractionTest; vn-edu.43,
    // LiveTranscriptPaneOverlongPartialTest; vn-edu.45, LiveTranscriptPaneWordFinalityTest)
    // and Roborazzi screenshot tests (vn-edu.34, `**/screenshot/**`) both need
    // androidx.compose.ui:ui-test-manifest's merged-in host Activity to launch
    // createComposeRule()'s content -- that library is debugImplementation-only
    // on purpose (shipping a test-only Activity declaration in the *release*
    // manifest would be worse than just not re-running these suites under the
    // release unit-test variant). The same AppNavHost/screens they exercise are
    // already fully covered under the debug variant. Any new test class that
    // calls createComposeRule() needs adding here too.
    if (name == "testReleaseUnitTest") {
        exclude("**/AppNavHostInteractionTest.class")
        exclude("**/LiveTranscriptPaneOverlongPartialTest.class")
        exclude("**/LiveTranscriptPaneWordFinalityTest.class")
        exclude("**/RecordingScreenTagsSlotTest.class")
        exclude("**/ApiKeyManagementRowTest.class")
        exclude("**/IntelligenceStepTest.class")
        exclude("**/screenshot/**")
    }
    if (!roborazziTaskRequested) {
        exclude("**/screenshot/**")
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
    // Compose interaction tests on Robolectric (vn-edu.35) -- JVM-only, no emulator/device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Roborazzi screenshot goldens (vn-edu.34) -- also JVM-only, no emulator/device.
    testImplementation(libs.roborazzi)
}
