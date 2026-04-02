// nacosbase-infra/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":nacosbase-core"))

    implementation("com.alibaba.nacos:nacos-client:2.4.3")

    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.55.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")

    implementation("com.charleskorn.kaml:kaml:0.61.0")

    implementation("org.json:json:20240303")

    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:mysql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            pom {
                name.set("nacosbase-infra")
                description.set("Nacos configuration version management — infrastructure adapters (Nacos, MySQL, CSV)")
                url.set("https://github.com/chaosmin/nacosbase")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("chaosmin")
                        name.set("Hugo.Min")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/chaosmin/nacosbase.git")
                    developerConnection.set("scm:git:ssh://github.com/chaosmin/nacosbase.git")
                    url.set("https://github.com/chaosmin/nacosbase")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/chaosmin/nacosbase")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_SIGNING_KEY")
    val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["release"])
    }
}

nmcp {
    publishAllPublications {
        username = System.getenv("MAVEN_CENTRAL_USERNAME") ?: ""
        password = System.getenv("MAVEN_CENTRAL_PASSWORD") ?: ""
        publicationType = "AUTOMATIC"
    }
}
