package com.jacey.game.gui.config

/**
 * GUI 客户端配置（原 propertyConfig.xml 固化为代码常量）
 *
 * serverHost/serverPort 为 GM 服务器 HTTP 地址：客户端启动时经 HTTP /gateway
 * 获取真正的网关连接地址。
 */
object GuiConfig {
    /** GM 服务器 HTTP 地址 */
    var serverHost: String = "127.0.0.1"
    var serverPort: Int = 80

    /** 心跳（秒）：写空闲 5s 发心跳包 */
    const val SOCKET_WRITER_IDLE_TIME = 5
}
