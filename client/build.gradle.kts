// Android library: the classic Bluetooth transport and the connection lifecycle on top of
// `protocol`. Third parties (including a future Wear OS app) depend on this to talk to a Sony headset
// without reimplementing the session.
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kover)
  `maven-publish`
}

group = "fi.nikosavola.xmheadphones"
version = System.getenv("RELEASE_VERSION")?.removePrefix("v") ?: "0.1.0-SNAPSHOT"

android {
  namespace = "fi.nikosavola.xmheadphones.client"
  compileSdk = 37
  defaultConfig { minSdk = 26 }
  publishing { singleVariant("release") { withSourcesJar() } }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Published for other clients, so the public surface is deliberate rather than whatever is not
// private yet.
kotlin { explicitApi() }

dependencies {
  api(project(":protocol"))
  api(libs.kotlinx.coroutines.android)
  implementation(libs.timber)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.karoo.ext.testing)
  testImplementation(libs.karoo.ext.testing.robolectric)
}

// AGP only registers the `release` software component after evaluation.
afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        pom {
          name = "Sony headset client"
          description =
            "Android client for Sony MDR headphones: the classic Bluetooth transport, the session " +
              "over it, and a state flow of what the headset reports."
          url = "https://github.com/nikosavola/karoo-xm-headphones"
          licenses {
            license {
              name = "Apache License 2.0"
              url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
          }
          scm { url = "https://github.com/nikosavola/karoo-xm-headphones" }
        }
      }
    }
  }
}

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/nikosavola/karoo-xm-headphones")
      credentials {
        username = System.getenv("GPR_USER")
        password = System.getenv("GPR_KEY")
      }
    }
  }
}
