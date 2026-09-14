plugins {
    id("java-conventions")
}

dependencies {
    api(project(":common"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
}
