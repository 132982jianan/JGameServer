package com.jacey.game.common.framework.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import com.jacey.game.common.framework.nacos.ConfigLoader
import com.jacey.game.common.framework.nacos.Config
import com.jacey.game.common.framework.process.Exit
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import org.bson.codecs.configuration.CodecRegistries
import java.util.concurrent.TimeUnit

/**
 * MongoDB 客户端（单例）——mongodb-driver-kotlin-coroutine
 *
 * 配置从 Nacos 加载（conf/mongo.yml），全部 API 协程化：
 * 业务侧 `val user = Db.users.findOne(...)` 写法同步、执行非阻塞。
 */
object Mongo {
    private val logger = KotlinLogging.logger {}

    lateinit var conf: MongoConfig
        private set
    private lateinit var client: MongoClient
    lateinit var db: MongoDatabase
        private set

    /** 连接配置（conf/mongo.yml）；环境变量 MONGO_URI/MONGO_HOST 优先 */
    @Serializable
    data class MongoConfig(
        val connectionString: String = System.getenv("MONGO_URI")?.takeIf { it.isNotBlank() }
            ?: ("mongodb://" + (System.getenv("MONGO_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1") + ":27017"),
        val databaseName: String = System.getenv("MONGO_DB") ?: "jgame_server",
    ) : Config

    fun init(): Boolean {
        conf = ConfigLoader.load<MongoConfig>() ?: return false
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(conf.connectionString))
            .applyToSocketSettings { s ->
                s.connectTimeout(10, TimeUnit.SECONDS)
                s.readTimeout(30, TimeUnit.SECONDS)
            }
            .applyToConnectionPoolSettings { s ->
                s.maxSize(100)
                s.minSize(10)
                s.maxWaitTime(5, TimeUnit.SECONDS)
            }
            .build()
        client = MongoClient.Factory.create(settings)
        db = client.getDatabase(conf.databaseName)
        // 连通性验证：ping 命令（协程 API，包一层 runCatching 不在 init 挂起上下文）
        val ok = kotlinx.coroutines.runBlocking {
            try {
                db.runCommand(org.bson.Document("ping", 1))
                true
            } catch (e: Exception) {
                logger.error(e) { "mongo connect fail: ${conf.connectionString}" }
                false
            }
        }
        if (!ok) return false
        logger.info { "mongo connected: db=${conf.databaseName}" }
        Exit.addExitListener { client.close() }
        return true
    }
}
