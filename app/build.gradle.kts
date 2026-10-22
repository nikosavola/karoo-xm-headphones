import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kover)
}

// A release build has to carry the same key as the one before it, or a Karoo refuses to update over
// it. The key is never in the repo: a local build reads keystore.properties, and CI writes that same
// file from its secrets before it builds.
val keystoreProperties = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

// A tag names the release and the libraries read the same variable. Left at a constant, every APK
// would report the first version, and a Karoo refuses an update whose version code has not climbed.
val releaseVersion = System.getenv("RELEASE_VERSION")?.removePrefix("v")

/**
 * Major, minor and patch as one climbing number, the shape Android compares.
 *
 * A trailing `-rc1` and the like is rejected rather than read as zero. Two tags that differ only
 * after the numbers would then get the same code, and the Karoo would refuse the second one.
 */
fun versionCodeOf(version: String): Int {
  val (major, minor, patch) =
    version.split('.').map {
      it.toIntOrNull()
        ?: error("RELEASE_VERSION `$version` is not numeric, so no version code can climb with it")
    } + listOf(0, 0, 0)
  return major * 10_000 + minor * 100 + patch
}

android {
  namespace = "fi.nikosavola.karoo.xmheadphones"
  compileSdk = 37

  defaultConfig {
    applicationId = "fi.nikosavola.karoo.xmheadphones"
    // The oldest platform the extension is meant to run on; the Karoo in hand reports API 32.
    minSdk = 26
    targetSdk = 37
    versionCode = releaseVersion?.let(::versionCodeOf) ?: 1
    versionName = releaseVersion ?: "0.1.0-SNAPSHOT"
  }

  signingConfigs {
    create("release") {
      if (keystoreProperties.isNotEmpty()) {
        storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Without a key the build still works, which is what the tests and the screenshot task need.
      signingConfig = signingConfigs.getByName(if (keystoreProperties.isNotEmpty()) "release" else "debug")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  // karoo-ext still asks for WorkManager 2.7.1, which brings Room 2.2.5 from 2020 and a consumer
  // rule that no longer matches what Room instantiates reflectively. The WorkManager API it uses
  // has not changed since, and this is what keeps the release build's R8 rules honest.
  constraints {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.work.runtime.ktx)
  }

  implementation(project(":protocol"))
  implementation(project(":client"))

  implementation(libs.hammerhead.karoo.ext)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.bundles.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.glance.appwidget)
  // No @Serializable class lives in this module, but karoo-ext's inline event consumers are compiled
  // into it and decode JSON there, so the runtime has to have this.
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.timber)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.karoo.ext.testing)
  testImplementation(libs.karoo.ext.testing.robolectric)
  // Declares the bare activity the compose test rule hosts, so Robolectric can resolve it.
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The screenshot tests draw into the build directory, not over the tracked images in
// docs/screenshots. Writing over them made every test run dirty the tree on any host whose
// rasterization differs, which is one accidental `git add -A` away from committing a screen nobody
// looked at. `recordScreenshots` is the deliberate way to update them.
val screenshotDir = layout.buildDirectory.dir("screenshots")

tasks.withType<Test>().configureEach {
  systemProperty("xmheadphones.screenshotDir", screenshotDir.get().asFile.absolutePath)
  // Declared as an output because Gradle does not otherwise know the test writes it: without this a
  // run that comes back FROM-CACHE, or is simply UP-TO-DATE, leaves a green build with no images in
  // the directory at all, and anything downstream comparing them is comparing nothing.
  outputs.dir(screenshotDir)
}

val recordScreenshots = tasks.register<Copy>("recordScreenshots") {
  group = "documentation"
  description = "Redraws the README's screenshots from the current composables."
  dependsOn(tasks.named("testDebugUnitTest"))
  from(screenshotDir)
  into(rootProject.layout.projectDirectory.dir("docs/screenshots"))
  // Excluding the test task makes this a copy of nothing, which Gradle skips as NO-SOURCE and
  // reports as a success, leaving the tracked images as they were. An action or an onlyIf predicate
  // would be skipped along with it, so nothing short of a custom task can say so instead.
}
