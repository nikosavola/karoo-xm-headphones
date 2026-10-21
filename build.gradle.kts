// AGP 9's built-in Kotlin would otherwise compile with whatever Kotlin it bundles. Pin ours so the
// serialization compiler plugin (which must match exactly) stays aligned. A literal because the
// version catalog isn't available this early; keep in sync with `kotlin` in gradle/libs.versions.toml.
buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") } }

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kover) apply false
  alias(libs.plugins.sonarqube)
}

sonar {
  properties {
    property("sonar.projectKey", "nikosavola_karoo-xm-headphones")
    property("sonar.organization", "nikosavola")
    // The same Kover reports CI uploads to Codecov. sonar-kotlin reads JaCoCo XML under this key.
    // Absolute, because the property is resolved against the module directory, not the root.
    property(
      "sonar.coverage.jacoco.xmlReportPaths",
      listOf(
          // protocol is a plain JVM module, so its report has no variant suffix.
          file("protocol/build/reports/kover/report.xml"),
          file("client/build/reports/kover/reportDebug.xml"),
          file("app/build/reports/kover/reportDebug.xml"),
        )
        .joinToString(",") { it.absolutePath },
    )
  }
}
