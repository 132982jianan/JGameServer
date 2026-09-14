plugins {
    id("java-conventions")
}

dependencies {
    api(project(":common"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}
