package com.jacey.game.common.framework.redis

import com.jacey.game.common.framework.nacos.Config
import kotlinx.serialization.Serializable

/** 连接配置（conf/redis.yml）；环境变量 REDIS_HOST 优先 */
@Serializable
data class RedisConfig(
    val host: String = System.getenv("REDIS_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
    val port: Int = 6379,
    val password: String = "",
) : Config