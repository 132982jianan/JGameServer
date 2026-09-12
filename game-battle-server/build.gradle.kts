plugins {
    id("java-conventions")
}

dependencies {
    api(project(":game-common"))
    api(project(":game-db"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
}
