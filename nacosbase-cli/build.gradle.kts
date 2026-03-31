// nacosbase-cli/build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.0.0-beta4"
    application
}

application {
    mainClass.set("com.nacosbase.cli.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":nacosbase-core"))
    implementation(project(":nacosbase-infra"))
    implementation("com.github.ajalt.clikt:clikt:4.4.0")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
}

tasks.shadowJar {
    archiveBaseName.set("nacosbase")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}
