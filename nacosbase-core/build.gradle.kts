// nacosbase-core/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation(kotlin("test"))
}
