plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.example.indoorar"
version = "1.0.0"

application {
    mainClass.set("com.example.indoorar.server.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))
    
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    
    implementation("ch.qos.logback:logback-classic:1.4.14")
}
