pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
    }
}

plugins {
    // Foojay 解析器：声明 toolchain JDK 21 后自动解析/下载对应 JDK，统一构建环境
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "jgame.server"

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

include(":common")
include(":portal")
include(":gate")
include(":lobby")
include(":global")
include(":battle")
include(":insight")
include(":server")
include(":TicTacToe-GUI")
