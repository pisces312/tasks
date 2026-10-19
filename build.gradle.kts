import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.redacted) apply false
    alias(libs.plugins.room) apply false
}

buildscript {
    dependencies {
        classpath(libs.gradle)
        classpath(libs.google.services)
        classpath(libs.firebase.crashlytics.gradle)
        classpath(libs.kotlin.gradle)
        classpath(libs.dagger.hilt.gradle)
        classpath(libs.oss.licenses.plugin)
    }
}

tasks.getByName<Wrapper>("wrapper") {
    gradleVersion = "9.8.1"
    distributionType = Wrapper.DistributionType.ALL
}

allprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            // Compose compiler metrics are opt-in via -PcomposeMetrics=<dir>.
            // Kotlin 2.4 names modules "<root>:<module>_<variant>"; the Compose plugin copies that
            // name into the metrics filename, and ':' is not a legal character in Windows paths,
            // so enabling metrics unconditionally breaks every Kotlin compile on Windows.
            val composeReports = project.properties["composeMetrics"] as String?
            if (composeReports != null) {
                freeCompilerArgs.addAll(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" + composeReports + "/compose-metrics",
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" + composeReports + "/compose-metrics",
                )
            }
        }
    }
}
