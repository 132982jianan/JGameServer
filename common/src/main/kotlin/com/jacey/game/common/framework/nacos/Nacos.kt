package com.jacey.game.common.framework.nacos

import com.alibaba.nacos.api.NacosFactory
import com.alibaba.nacos.api.PropertyKeyConst
import com.alibaba.nacos.api.config.ConfigService
import com.alibaba.nacos.api.naming.NamingService
import com.jacey.game.common.framework.process.Exit
import java.util.Properties

/**
 * Nacos 注册中心与配置中心客户端（单例）
 *
 * - naming: 服务注册与发现（节点上下线通知）
 * - config: 配置管理（集中配置 + 热更新）
 */
object Nacos {
    lateinit var conf: NacosConfig
        private set
    lateinit var naming: NamingService
        private set
    lateinit var config: ConfigService
        private set

    /** 初始化 Nacos 连接（读取本地 nacos.yml） */
    fun init(): Boolean {
        // 关闭 Nacos 自身日志噪音
        System.setProperty("nacos.logging.default.config.enabled", "false")
        System.setProperty("nacos.common.processors", "1")
        System.setProperty("nacos.remote.client.grpc.pool.core.size", "2")
        System.setProperty("nacos.remote.client.grpc.pool.max.size", "8")
        conf = ConfigLoaderService.loadLocal<NacosConfig>() ?: return false
        val properties = Properties()
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, "${conf.host}:${conf.port}")
        properties.setProperty(PropertyKeyConst.NAMESPACE, conf.namespace)
        if (conf.username.isNotEmpty()) properties.setProperty(PropertyKeyConst.USERNAME, conf.username)
        if (conf.password.isNotEmpty()) properties.setProperty(PropertyKeyConst.PASSWORD, conf.password)
        naming = NacosFactory.createNamingService(properties)
        config = NacosFactory.createConfigService(properties)
        Exit.addExitListener { close() }
        return true
    }

    /** 关闭 Nacos 连接 */
    private fun close() {
        if (this::config.isInitialized) config.shutDown()
        if (this::naming.isInitialized) naming.shutDown()
    }
}
