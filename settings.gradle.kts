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

include(":game-common")
include(":game-db")
include(":game-gm-server")
include(":game-logic-server")
include(":game-battle-server")
include(":game-chat-server")
include(":game-gateway-server")
include(":server")
include(":TicTacToe-GUI")
