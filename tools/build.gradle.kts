// Инструмент для проверки умных границ на настоящих роликах (запускается в GitHub Actions).
plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("app.clipradar.tools.RealVideoKt")
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
}
