// buildSrc 自身构建配置：把 buildSrc 当作 Gradle 插件工程，产出 java-conventions 预编译脚本插件
repositories {
    maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    gradlePluginPortal()
}

plugins {
    id("java-gradle-plugin")
    `kotlin-dsl`
}

val kotlinVersion = "2.3.0"

dependencies {
    // 让约定插件可以使用 Kotlin Gradle 插件 API（kotlin("jvm") 等）
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    // 序列化编译器插件（plugin.serialization）
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
}
