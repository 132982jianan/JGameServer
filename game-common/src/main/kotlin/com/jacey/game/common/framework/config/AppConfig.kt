package com.jacey.game.common.framework.config

import com.jacey.game.common.framework.nacos.IConfig
import com.jacey.game.common.framework.nacos.ConfigLoaderService
import kotlinx.serialization.Serializable

/**
 * 业务公共配置（conf/app.yml）
 *
 * 原 propertyConfig.xml + config.properties 的业务项归并。
 * 从 Nacos 加载（首次自动发布默认值）。
 */
@Serializable
data class AppConfig(
    /** socket 读空闲（秒） */
    val socketReaderIdleTime: Int = 0,
    /** socket 写空闲（秒） */
    val socketWriterIdleTime: Int = 0,
    /** socket 全空闲（秒） */
    val socketAllIdleTime: Int = 300,
    /** 逻辑服默认 id */
    val logicServerId: Int = 1,
    /** 是否主逻辑服 */
    val isMainLogicServer: Boolean = true,
    /** 对战服默认 id */
    val battleServerId: Int = 1,
    /** 聊天服默认 id */
    val chatServerId: Int = 1,
    /** 网关默认 id */
    val gatewayId: Int = 1,
    /** 网关对外连接地址（客户端从 GM 获取） */
    val gatewayConnectPath: String = "127.0.0.1:10001",
) : IConfig {
    companion object {
        /** 惰性加载（首次访问时从 Nacos/conf 读取） */
        val instance: AppConfig by lazy {
            ConfigLoaderService.load<AppConfig>() ?: AppConfig()
        }
    }
}
