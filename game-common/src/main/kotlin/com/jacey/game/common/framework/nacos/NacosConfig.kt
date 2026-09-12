package com.jacey.game.common.framework.nacos

import kotlinx.serialization.Serializable

/**
 * Nacos 连接配置（对应 conf/nacos.yml，仅本地加载，不经过 Nacos 本身）
 */
@Serializable
data class NacosConfig(
    /** Nacos 服务器地址（环境变量 NACOS_HOST 优先） */
    val host: String = System.getenv("NACOS_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
    /** Nacos 端口 */
    val port: Int = 8848,
    /** 命名空间 */
    val namespace: String = "public",
    /** 分组 */
    val group: String = "DEFAULT_GROUP",
    /** 用户名 */
    val username: String = "nacos",
    /** 密码 */
    val password: String = "nacos",
) : Config
