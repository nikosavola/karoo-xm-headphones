// Pure JVM (no Android): the Sony frame codec and the command/payload definitions. Kept free of
// platform types so the same artifact can back an Android client, a Wear OS app or a desktop tool.
plugins {
  alias(libs.plugins.kotlin.jvm)
  `maven-publish`
}

kotlin {
  explicitApi()
  jvmToolchain(17)
}

java {
  withSourcesJar()
  withJavadocJar()
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

publishing {
  publications {
    create<MavenPublication>("maven") { from(components["java"]) }
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
