plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "fi.nikosavola.karoo.xmheadphones"
  compileSdk = 37

  defaultConfig {
    applicationId = "fi.nikosavola.karoo.xmheadphones"
    // The oldest platform the extension is meant to run on; the Karoo in hand reports API 32.
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
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
