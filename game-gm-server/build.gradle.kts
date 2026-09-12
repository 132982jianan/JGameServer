plugins {
    id("java-conventions")
}

dependencies {
    api(project(":game-common"))
    api(project(":game-db"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
    api(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}
