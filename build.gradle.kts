// build.gradle.kts
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.gradleup.nmcp") version "0.1.3" apply false
}

allprojects {
    group = "io.github.chaosmin"
    version = project.findProperty("nacosbase.version") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "jacoco")

    afterEvaluate {
        extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>()
            ?.jvmToolchain(21)
        tasks.withType<Test> {
            useJUnitPlatform()
            finalizedBy(tasks.named("jacocoTestReport"))
        }
        tasks.named<JacocoReport>("jacocoTestReport") {
            reports {
                xml.required.set(true)
                html.required.set(false)
            }
        }
    }
}
