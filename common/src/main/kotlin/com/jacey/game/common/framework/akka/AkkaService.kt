package com.jacey.game.common.framework.akka

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Akka 系统管理（单例）
 *
 * - start(): 按 node kind/id 生成 artery 配置并创建 ActorSystem
 * - create(): 在系统内创建 CoroutineActor 子类的 actor
 * - addressOf(): 生成本节点 artery path（注册到 Nacos metadata 用）
 */
object AkkaService {
    private val logger = KotlinLogging.logger {}
    lateinit var system: ActorSystem
        private set

    /**
     * 启动 ActorSystem
     *
     * @param kind        节点类型名（portal/gate/lobby/global/battle/insight），作为 actor system 名后缀
     * @param nodeId      节点 id
     * @param arteryPort  artery 监听端口（0 表示不启用 remote，纯本地）
     * @param hostname    artery canonical hostname（Docker 场景由环境变量注入）
     */
    fun start(kind: String, nodeId: Int, arteryPort: Int, hostname: String, loglevel: String = "INFO"): ActorSystem {
        val akkaConf = if (arteryPort > 0) {
            remoteConf(hostname, arteryPort, loglevel)
        } else {
            localConf(loglevel)
        }
        system = ActorSystem.create("${kind}_$nodeId", akkaConf)
        logger.info { "akka system started: ${system.name()}, artery=$hostname:$arteryPort" }
        return system
    }

    /** 创建 CoroutineActor 子类 actor（返回 ActorRef，通信走 askAwait/tell） */
    inline fun <reified A : CoroutineActor> create(name: String): ActorRef {
        return system.actorOf(Props.create(A::class.java), name)
    }

    /** 生成 artery actor path：akka://<sys>@<host>:<port>/user/<name> */
    fun addressOf(systemName: String, hostname: String, port: Int, actorName: String): String {
        return "akka://$systemName@$hostname:$port/user/$actorName"
    }

    /**
     * remote（artery tcp）配置片段
     * 全部由代码内 HOCON 生成（配置值来源于 Nacos 的 net.yml），无外部 conf 文件依赖。
     */
    private fun remoteConf(hostname: String, port: Int, loglevel: String): Config = ConfigFactory.parseString(
        """
        akka {
          loglevel = "$loglevel"
          log-dead-letters = 10
          actor {
            provider = remote
            allow-java-serialization = off
            serializers {
              net = "com.jacey.game.common.serialize.NetMessageSerializer"
              remote = "com.jacey.game.common.serialize.RemoteMessageSerializer"
            }
            serialization-bindings {
              "com.jacey.game.common.msg.NetMessage" = net
              "com.jacey.game.common.msg.RemoteMessage" = remote
            }
          }
          remote {
            artery {
              enabled = on
              transport = tcp
              canonical {
                hostname = "$hostname"
                port = $port
              }
            }
          }
          remote.artery.advanced.aeron.term-buffer-length = 4194304
        }
        """.trimIndent()
    ).withFallback(ConfigFactory.parseResources("akka-merged.conf"))

    /** 纯本地配置片段（无 remote 需求的节点） */
    private fun localConf(loglevel: String): Config = ConfigFactory.parseString(
        """
        akka {
          loglevel = "$loglevel"
          actor {
            provider = local
            allow-java-serialization = off
            serializers {
              net = "com.jacey.game.common.serialize.NetMessageSerializer"
              remote = "com.jacey.game.common.serialize.RemoteMessageSerializer"
            }
            serialization-bindings {
              "com.jacey.game.common.msg.NetMessage" = net
              "com.jacey.game.common.msg.RemoteMessage" = remote
            }
          }
        }
        """.trimIndent()
    )

    /** 优雅关闭 */
    suspend fun close() {
        if (this::system.isInitialized) {
            system.terminate()
        }
    }
}
