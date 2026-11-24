plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.unstablesmp"
version = "4.3.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") // also proxies Maven Central
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked", "-Xlint:removal"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("UnstableSMP-${project.version}.jar")
}

tasks.runServer {
    minecraftVersion("1.21.11")
    jvmArgs("-Dcom.mojang.eula.agree=true", "-Xms2G", "-Xmx2G")
}
