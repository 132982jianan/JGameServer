plugins {
    id("java-conventions")
}

dependencies {
    api(project(":common"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
    api(libs.io.netty.netty.all)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}
