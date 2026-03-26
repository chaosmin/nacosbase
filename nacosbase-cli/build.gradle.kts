// nacosbase-cli/build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.0.0-beta4"
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.nacosbase.cli.MainKt")
}

dependencies {
    implementation(project(":nacosbase-core"))
    implementation(project(":nacosbase-infra"))
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("nacosbase")
    archiveClassifier.set("")
    archiveVersion.set("0.1.0")
}
