// nacosbase-infra/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":nacosbase-core"))

    implementation("com.alibaba.nacos:nacos-client:2.4.3")

    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.55.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")

    implementation("com.charleskorn.kaml:kaml:0.61.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")

    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:mysql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

tasks.test {
    useJUnitPlatform()
}
