pluginManagement {
    val useCnMirrors =
        (System.getenv("LINPLAYER_USE_CN_MIRRORS") ?: "")
            .trim()
            .lowercase()
            .let { it == "1" || it == "true" || it == "yes" }

    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        if (useCnMirrors) {
            // Helpful for networks that can't reach dl.google.com / services.gradle.org reliably.
            maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
            maven(url = uri("https://maven.aliyun.com/repository/google"))
            maven(url = uri("https://maven.aliyun.com/repository/central"))
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")
