import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logique pure (vision, règles, protocole UCI), sans dépendance Android : testable sur la JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
