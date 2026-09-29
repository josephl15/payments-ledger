plugins {
    // Compiles Java and runs tests.
    java
    // Spring Boot plugin: Boot BOM (dependency versions), bootJar (runnable fat jar), bootRun.
    id("org.springframework.boot") version "3.5.16"
    // Applies the Boot BOM so the dependencies below need no version numbers.
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.joseph"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        // Pins the compiler to Java 21 whatever JDK runs Gradle itself.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web (Spring MVC + embedded Tomcat + Jackson), JPA (Hibernate + Spring Data), Actuator (/actuator/health), Bean Validation.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Flyway: versioned SQL migrations. The PostgreSQL module is separate since Flyway 10 (without it: "Unsupported Database").
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL JDBC driver, needed only at runtime.
    runtimeOnly("org.postgresql:postgresql")

    // Test stack: JUnit 5, AssertJ, Mockito, Spring Test (all via the Boot test starter).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Testcontainers 1.x artifact names: a real PostgreSQL in Docker for integration tests, versions from the Boot BOM.
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // Launcher Gradle 9 requires; harmless on 8.14, avoids a break on a later Gradle bump.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // Without this Gradle finds no JUnit 5 tests and reports success with zero tests run.
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.bootJar {
    // Stable name so the Dockerfile can COPY build/libs/app.jar.
    archiveFileName = "app.jar"
}
