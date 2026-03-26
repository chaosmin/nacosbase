// build.gradle.kts
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    version = project.findProperty("nacosbase.version") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    afterEvaluate {
        extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
            ?.jvmToolchain(21)
        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}
