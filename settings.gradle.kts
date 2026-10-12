pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // karoo-ext's GitHub Packages feed needs a token even for public reads; JitPack builds the same
    // tags without one.
    exclusiveContent {
      forRepository { maven("https://jitpack.io") }
      filter {
        includeGroup("com.github.hammerheadnav")
        includeGroup("com.github.nikosavola.karoo-ext-testing")
      }
    }
  }
}

rootProject.name = "karoo-xm-headphones"

include(":protocol")
include(":client")
include(":app")
