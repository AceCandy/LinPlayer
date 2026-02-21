val useCnMirrors =
    (System.getenv("LINPLAYER_USE_CN_MIRRORS") ?: "")
        .trim()
        .lowercase()
        .let { it == "1" || it == "true" || it == "yes" }

allprojects {
    buildscript {
        repositories {
            if (useCnMirrors) {
                // Helpful for networks that can't reach dl.google.com reliably.
                maven(url = uri("https://maven.aliyun.com/repository/gradle-plugin"))
                maven(url = uri("https://maven.aliyun.com/repository/google"))
                maven(url = uri("https://maven.aliyun.com/repository/central"))
            }
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    repositories {
        if (useCnMirrors) {
            maven(url = uri("https://maven.aliyun.com/repository/google"))
            maven(url = uri("https://maven.aliyun.com/repository/central"))
        }
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
