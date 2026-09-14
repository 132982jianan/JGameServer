plugins {
    id("java-conventions")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.io.github.oshai.kotlin.logging.jvm)
    implementation(libs.slf4j.api)
    api(libs.log4j.core)
    implementation(libs.log4j.slf4j2.impl)
    implementation(libs.log4j.layout.template.json)
    api(libs.com.google.protobuf.protobuf.java)
    implementation(libs.com.google.protobuf.protobuf.kotlin)
    implementation(libs.com.google.protobuf.protobuf.java.util)
    // Akka classic + remote（artery），classic API 兼容原 2.5 代码
    api(libs.akka.classic)
    api(libs.akka.remote)
    implementation(libs.akka.slf4j)
    // 协程 <-> akka CompletionStage 桥接
    implementation(libs.kotlinx.coroutines.core)
    // 配置中心
    implementation(libs.nacos.client)
    implementation(libs.kaml)
    // 网络（Netty 由 Gate 使用；common 内 Netty 编解码也用）
    // MongoDB Kotlin 协程驱动（framework 的 Mongo/DbCollection 封装所在模块）
    api(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.mongodb.driver.core)
    implementation(libs.bson)
    // Redis：Lettuce 协程 API
    api(libs.lettuce.core)
    implementation(libs.io.netty.netty.all)
    // HTTP（Ktor），GM 服务器用
    api(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

protobuf {
    protoc {
        // 版本与 libs.versions.toml 的 protobuf 一致（catalog 访问器在此 DSL 块不可用，直接写死并注释保持同步）
        artifact = "com.google.protobuf:protoc:4.33.0"
    }
    generateProtoTasks {
        all().forEach {
            it.builtins {
                create("kotlin")
            }
        }
    }
}
