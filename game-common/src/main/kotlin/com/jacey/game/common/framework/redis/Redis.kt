package com.jacey.game.common.framework.redis

import com.jacey.game.common.framework.nacos.ConfigLoaderService
import com.jacey.game.common.framework.process.Exit
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import java.time.Duration

/**
 * Redis 客户端（单例）——Lettuce 协程 API
 *
 * 命令对象 Redis.commands 全部为挂起函数：
 * `Redis.commands.set(key, value)` 同步写法、非阻塞。
 * key 统一包装为 @JvmInline Key，避免裸 String 误用。
 */
object Redis {
    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<Key, String>

    lateinit var conf: RedisConfig
        private set

    /** 协程化命令集（get/set/hset/expire 等） */
    lateinit var commands: RedisCoroutinesCommands<Key, String>
        private set

    fun key(str: String): Key {
        return Key(str)
    }

    fun init(): Boolean {
        conf = ConfigLoaderService.load<RedisConfig>() ?: return false
        val uri = RedisURI.builder()
            .withHost(conf.host)
            .withPort(conf.port)
            .withTimeout(Duration.ofSeconds(10))
            .apply { if (conf.password.isNotEmpty()) withPassword(conf.password.toCharArray()) }
            .build()
        client = RedisClient.create(uri)
        connection = try {
            client.connect(object : io.lettuce.core.codec.RedisCodec<Key, String> {
                private val strCodec = io.lettuce.core.codec.StringCodec.UTF8
                override fun decodeKey(bytes: java.nio.ByteBuffer) = Key(strCodec.decodeKey(bytes))
                override fun encodeKey(key: Key) = strCodec.encodeKey(key.str)
                override fun decodeValue(bytes: java.nio.ByteBuffer) = strCodec.decodeValue(bytes)
                override fun encodeValue(value: String) = strCodec.encodeValue(value)
            })
        } catch (e: Exception) {
            KotlinLogging.logger {}.error(e) { "redis connect fail: ${conf.host}:${conf.port}" }
            return false
        }
        commands = connection.coroutines()
        // 连通性验证（协程命令，init 非 suspend，用 runBlocking 包一层）
        val ok = kotlinx.coroutines.runBlocking {
            try {
                commands.ping() == "PONG"
            } catch (e: Exception) {
                false
            }
        }
        if (!ok) {
            KotlinLogging.logger {}.error { "redis ping fail: ${conf.host}:${conf.port}" }
            return false
        }

        KotlinLogging.logger {}.info { "redis connected: ${conf.host}:${conf.port}" }

        Exit.addExitListener {
            connection.close()
            client.shutdown()
        }
        return true
    }
}
