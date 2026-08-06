import java.util.Properties
import java.util.zip.ZipFile

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

        // ASSEMBLYAI_API_KEY / ANTHROPIC_API_KEY / GITHUB_TOKEN are NOT set
        // here (bead vn-edu.53): a defaultConfig buildConfigField applies to
        // every variant, which meant a release APK built on a machine with a
        // populated local.properties shipped real credentials. They're set
        // per build type below instead -- debug keeps today's
        // local.properties/env convenience, release is hardcoded to "" so no
        // secret can ever reach a release build regardless of environment.
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
        debug {
            // Dev convenience only (bead vn-edu.53): a fresh `./gradlew
            // assembleDebug` picks up local.properties/env secrets so the
            // emulator/device harness works with zero in-app setup. See
            // README's "Configuring secrets for the live demo".
            buildConfigField("String", "ASSEMBLYAI_API_KEY", "\"${localProperty("assemblyai.apiKey", "ASSEMBLYAI_API_KEY")}\"")
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"${localProperty("anthropic.apiKey", "ANTHROPIC_API_KEY")}\"")
            buildConfigField("String", "GITHUB_TOKEN", "\"${localProperty("github.token", "GITHUB_TOKEN")}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Always blank, independent of local.properties/env (bead
            // vn-edu.53) -- release ships with no baked secrets; users
            // configure keys in-app instead (AppSecretsStore, vn-edu.48).
            // ReleaseSecretsBlankTest and the checkReleaseSecretsAbsent task
            // below both guard against this regressing.
            buildConfigField("String", "ASSEMBLYAI_API_KEY", "\"\"")
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
            buildConfigField("String", "GITHUB_TOKEN", "\"\"")
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
        // Release-only unit tests (bead vn-edu.53): testReleaseUnitTest
        // compiles against the *release* variant's generated BuildConfig, so
        // a test that only makes sense for that variant (secrets must be
        // blank) lives here instead of src/test, which is shared by every
        // variant's unit-test task including testDebugUnitTest, where the
        // same assertion would legitimately fail.
        getByName("testRelease") {
            kotlin.srcDirs("src/testRelease/kotlin")
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
        exclude("**/RecordingScreenTranscriptSlotTest.class")
        exclude("**/RecordingScreenTagRailTest.class")
        exclude("**/ApiKeyManagementRowTest.class")
        exclude("**/SettingsCredentialRowsTest.class")
        exclude("**/IntelligenceStepTest.class")
        exclude("**/LoginScreenTest.class")
        exclude("**/SessionListVaultStatusTest.class")
        exclude("**/SessionDetailTabsTest.class")
        exclude("**/SessionDeleteInteractionTest.class")
        exclude("**/SessionSwipeInteractionTest.class")
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

// Bead vn-edu.53: ReleaseSecretsBlankTest (app/src/testRelease) already
// guards BuildConfig itself, but this task inspects the actual built
// artifact -- belt-and-suspenders against anything (a proguard/R8 rule, a
// resource merge, a future secret added elsewhere) re-embedding a real
// secret value in the bytes that ship. It reads expected values from
// local.properties at runtime rather than inlining them here, so no secret
// is ever committed. On a machine with no secrets configured (e.g. CI,
// which never checks in local.properties) there's nothing to look for, so
// the task skips rather than false-passing on an empty comparison.
tasks.register("checkReleaseSecretsAbsent") {
    group = "verification"
    description = "Assembles release and asserts secrets configured in local.properties/env do not appear in the output APK. Skips gracefully if none are configured (e.g. CI). See README's secrets section."
    dependsOn("assembleRelease")
    doLast {
        val secretKeys = mapOf(
            "assemblyai.apiKey" to "ASSEMBLYAI_API_KEY",
            "anthropic.apiKey" to "ANTHROPIC_API_KEY",
            "github.token" to "GITHUB_TOKEN",
        )
        val secrets = secretKeys.map { (fileKey, envFallback) -> localProperty(fileKey, envFallback) }
            .filter { it.isNotBlank() }
            .toSet()
        if (secrets.isEmpty()) {
            logger.lifecycle("checkReleaseSecretsAbsent: no secrets configured in local.properties/env; nothing to scan for, skipping.")
            return@doLast
        }
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apk = apkDir.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            ?: throw GradleException("checkReleaseSecretsAbsent: no release APK found under $apkDir")
        val leakedIn = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                // Secrets here are ASCII API keys/tokens; ISO-8859-1 maps
                // bytes 1:1 to chars so a raw substring search works
                // regardless of the entry's actual encoding (dex, arsc, etc).
                val text = zip.getInputStream(entry).use { it.readBytes() }
                    .toString(Charsets.ISO_8859_1)
                if (secrets.any { text.contains(it) }) {
                    leakedIn += entry.name
                }
            }
        }
        if (leakedIn.isNotEmpty()) {
            throw GradleException(
                "Release APK ${apk.name} leaked a configured secret in: ${leakedIn.joinToString(", ")}",
            )
        }
        logger.lifecycle("checkReleaseSecretsAbsent: verified ${secrets.size} configured secret(s) absent from ${apk.name}.")
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
