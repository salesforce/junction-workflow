/*
 *
 *  * Copyright (c) 2022, salesforce.com, inc.
 *  * All rights reserved.
 *  * Licensed under the BSD 3-Clause license.
 *  * For full license text, see LICENSE.txt file in the repo root or
 *  * https://opensource.org/licenses/BSD-3-Clause
 *
 */

import com.google.protobuf.gradle.*
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
    id("com.google.protobuf").version("0.8.19")
    java
    `jvm-test-suite`
    `maven-publish`
    id("com.github.jk1.dependency-license-report").version("2.1")
    idea
}

repositories {
    mavenCentral()
}

dependencies {
    // NOTE: There are more versions declared in `gradle/libs.versions.toml`

    implementation(platform("org.junit:junit-bom:5.8.2"))
    implementation(platform("com.google.protobuf:protobuf-bom:3.19.1"))
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.13.4"))

    // These all have BOM POMs, do NOT declare a version after the `group:artifact:`.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.google.protobuf:protobuf-java")

    // These don't have BOM POMs, they need versions:
    implementation("guru.nidi:graphviz-java:0.18.1")
    implementation("org.apache.kafka:kafka-clients:2.8.1")
    implementation("org.kohsuke:github-api:1.304")
    implementation("io.kubernetes:client-java:16.0.2")
    implementation("com.google.guava:guava:31.1-jre")
    implementation("org.quartz-scheduler:quartz:2.3.2")
    implementation("org.apache.logging.log4j:log4j-api:2.18.0")
    implementation("org.apache.logging.log4j:log4j-core:2.18.0")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.18.0")
    implementation("io.vertx:vertx-core:4.3.3")
    implementation("io.vertx:vertx-web:4.3.3")
    implementation("io.vertx:vertx-web-client:4.3.3")

    // Unit Test stuff goes in the `testing{}` block below!
}

group = "com.salesforce.junction-workflow"
version = "1.0-SNAPSHOT"
description = "junction-workflow"

tasks.compileJava {
    options.release.set(17); // JDK 17
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

protobuf {
    // Configure the protoc executable
    protoc {
        artifact = "com.google.protobuf:protoc:3.0.0"
    }
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation("io.vertx:vertx-junit5:4.2.4")
                implementation("org.junit.jupiter:junit-jupiter:5.8.2")
                implementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
                implementation("org.mockito:mockito-all:1.10.19")
            }
            targets {
                all {
                    testTask.configure {
                        testLogging {
                            events(FAILED, STANDARD_ERROR, SKIPPED)
                            exceptionFormat = FULL
                            showExceptions = true
                            showCauses = true
                            showStackTraces = true
                        }
                    }
                }
            }
        }
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project)
                implementation("org.junit.jupiter:junit-jupiter:5.8.2")
                implementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
                implementation("org.quartz-scheduler:quartz:2.3.2")
                implementation("com.github.charithe:kafka-junit:4.2.3")
                implementation("io.kubernetes:client-java:14.0.1")
                implementation("org.mockito:mockito-all:1.10.19")
                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.4")
            }
            targets {
                all {
                    testTask.configure {
                        testLogging {
                            showStandardStreams = true
                        }
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

task("copyDependencies", Copy::class) {
    configurations.compileClasspath.get()
        .filter { it.extension == "jar" }
        .forEach { from(it.absolutePath).into("$buildDir/dependencies") }
}

tasks.named("build") {
    dependsOn("copyDependencies")
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}
