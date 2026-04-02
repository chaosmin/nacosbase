// nacosbase-core/build.gradle.kts
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.yaml:snakeyaml:2.3")
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            pom {
                name.set("nacosbase-core")
                description.set("Nacos configuration version management — core domain layer")
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
    publish("release") {
        username = System.getenv("MAVEN_CENTRAL_USERNAME") ?: ""
        password = System.getenv("MAVEN_CENTRAL_PASSWORD") ?: ""
        publicationType = "AUTOMATIC"
    }
}
