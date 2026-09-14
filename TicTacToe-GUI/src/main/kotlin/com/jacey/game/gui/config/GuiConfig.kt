package com.jacey.game.gui.config

/**
 * GUI 客户端配置（原 propertyConfig.xml 固化为代码常量）
 *
 * serverHost/serverPort 为 Portal HTTP 地址：客户端启动时经 HTTP /gate 获取 Gate 地址。
 */
object GuiConfig {
    /** Portal HTTP 地址 */
    var serverHost: String = System.getenv("PORTAL_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    var serverPort: Int = System.getenv("PORTAL_PORT")?.toIntOrNull() ?: 8080

    /** 心跳（秒）：写空闲 5s 发心跳包 */
    const val SOCKET_WRITER_IDLE_TIME = 5
}
