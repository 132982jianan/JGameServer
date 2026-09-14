plugins {
    id("java-conventions")
    application
}

dependencies {
    // 复用服务端公共模块：消息编解码/protobuf 生成类/工具
    api(project(":common"))
    implementation(libs.io.github.oshai.kotlin.logging.jvm)
    implementation(libs.io.netty.netty.all)
    implementation(libs.slf4j.api)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j2.impl)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

application {
    // Swing 客户端非 headless
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=false")
    mainClass.set("com.jacey.game.gui.GuiApplicationKt")
}
