package com.jacey.game.common.framework.nacos

import com.alibaba.nacos.api.config.ConfigType
import com.alibaba.nacos.api.config.listener.Listener
import com.charleskorn.kaml.Yaml
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.Executor

/**
 * 配置加载器（单例）
 *
 * 优先级：Nacos > 本地 conf/ 目录 > classpath conf/ 目录。
 * 关键机制（默认发布）：Nacos 上不存在某配置时，把本地默认值发布到 Nacos；
 * 此后运维只修改 Nacos 上的配置即可，仓库里的 conf/ 只是首次默认值。
 *
 * 配置文件名约定：类名去 Config 后缀小写，如 MongoConfig -> mongo.yml
 */
object ConfigLoader {
    val logger = KotlinLogging.logger {}
    const val DEFAULT_TIMEOUT = 3000L

    /** 加载并监听配置变更（变更时用新配置回调 block） */
    inline fun <reified T : Config> listen(crossinline block: (T) -> Unit): T? {
        val name = configName<T>()
        try {
            val listener = object : Listener {
                override fun getExecutor(): Executor = Executor { it.run() }
                override fun receiveConfigInfo(configInfo: String?) {
                    if (configInfo == null) return
                    try {
                        block(Yaml.default.decodeFromString(serializer<T>(), configInfo))
                    } catch (e: Exception) {
                        logger.error(e) { "listen $name decode fail" }
                    }
                }
            }
            val str = Nacos.config.getConfigAndSignListener(name, Nacos.conf.group, DEFAULT_TIMEOUT, listener)
                ?: return null
            return Yaml.default.decodeFromString(serializer<T>(), str)
        } catch (e: Exception) {
            logger.error(e) { "listen $name from nacos fail" }
            return null
        }
    }

    /**
     * 加载配置：Nacos > 本地 conf/ > classpath conf/
     * Nacos 无此配置且本地有默认值时，自动把默认值发布到 Nacos（默认发布机制）
     */
    inline fun <reified T : Config> load(): T? {
        val name = configName<T>()
        val nacosStr = try {
            logger.debug { "try loading from nacos $name" }
            Nacos.config.getConfig(name, Nacos.conf.group, DEFAULT_TIMEOUT)
        } catch (e: Exception) {
            logger.error(e) { "load $name from nacos fail" }
            return null // Nacos 连接出错时不回退本地，避免"半配置"状态
        }
        val str = if (nacosStr == null) {
            logger.debug { "try loading from local $name" }
            readLocal(name)
        } else {
            nacosStr
        } ?: return null
        val conf = try {
            Yaml.default.decodeFromString(serializer<T>(), str)
        } catch (e: Exception) {
            logger.error(e) { "load $name decode fail" }
            return null
        }
        // 默认发布：Nacos 上没有该配置时，把本地默认值发布上去
        if (nacosStr == null) {
            try {
                val ok = Nacos.config.publishConfig(name, Nacos.conf.group, str, ConfigType.YAML.type)
                if (ok) logger.info { "publishConfig $name success" }
                else logger.error { "publishConfig $name fail" }
            } catch (e: Exception) {
                logger.error(e) { "publish $name fail" }
            }
        }
        return conf
    }

    /** 仅读取本地配置（conf/ 目录或 classpath），不访问 Nacos。用于 nacos.yml 自身 */
    inline fun <reified T : Config> loadLocal(): T? {
        val name = configName<T>()
        val str = readLocal(name) ?: return null
        return try {
            Yaml.default.decodeFromString(serializer<T>(), str)
        } catch (e: Exception) {
            logger.error(e) { "loadLocal $name decode fail" }
            null
        }
    }

    /** MongoConfig -> "mongo" */
    inline fun <reified T : Config> configName(): String =
        T::class.simpleName!!.lowercase().removeSuffix("config")

    /** 依次查找 ../conf/、conf/ 目录与 classpath conf/ 下的 yml 文件 */
    fun readLocal(name: String): String? {
        val dirList = listOf("../conf/", "conf/")
        val filename = "$name.yml"
        try {
            // 1. jar 外置配置文件（部署目录 conf/）
            dirList.forEach { dir ->
                val file = File(dir, filename)
                if (file.exists()) {
                    logger.debug { "load $filename from ${file.path}" }
                    return file.readText()
                }
            }
            // 2. classpath（打进 jar 的 resources/conf/ 默认值）
            dirList.forEach { dir ->
                val url = ConfigLoader::class.java.classLoader.getResource("$dir$filename")
                if (url != null) {
                    logger.debug { "load $filename from $url" }
                    return url.readText()
                }
            }
            return null
        } catch (e: Exception) {
            logger.error(e) { "read $filename fail" }
            return null
        }
    }
}
