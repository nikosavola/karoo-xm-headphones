// Pure JVM (no Android): the Sony frame codec and the command/payload definitions. Kept free of
// platform types so the same artifact can back an Android client, a Wear OS app or a desktop tool.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kover)
  `maven-publish`
}

group = "fi.nikosavola.xmheadphones"
version = System.getenv("RELEASE_VERSION")?.removePrefix("v") ?: "0.1.0-SNAPSHOT"

kotlin {
  explicitApi()
  jvmToolchain(17)
}

java { withSourcesJar() }

dependencies {
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      pom {
        name = "Sony headset protocol"
        description =
          "Frame codec and command definitions for the Sony MDR control protocol. No Android or " +
            "other platform types, so it runs on any JVM target."
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
