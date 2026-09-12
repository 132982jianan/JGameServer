plugins {
    id("java-conventions")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(project(":game-common"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
    // Redis：Lettuce 协程 API
    api(libs.lettuce.core)
    // MongoDB Kotlin 协程驱动
    api(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.mongodb.driver.core)
    implementation(libs.bson)
}
