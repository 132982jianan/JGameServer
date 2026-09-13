package com.jacey.game.server

import com.jacey.game.common.framework.nacos.ConfigLoader
import com.jacey.game.common.framework.nacos.Nacos
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.common.framework.process.Log4j2
import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.redis.Redis
import com.jacey.game.common.framework.net.NodeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 各节点通用启动序列（替代原 Spring Boot 启动 + CoreManager）
 *
 * 顺序：日志 → 信号 → Nacos → 节点注册(含 akka) → Redis → Mongo → 业务启动(actors/HTTP/Netty)
 */
object CommonStart {
    suspend fun start(kind: NodeKind, requestedId: Int?): Boolean {
        // 1. 日志（logs/<kind>/<kind>.log）
        Log4j2.init(kind.name)

        // 2. 退出信号（TERM/INT）
        Exit.listenSignal()

        // 3. Nacos 连接（读本地 nacos.yml）
        if (!Nacos.init()) {
            System.err.println("nacos connect fail, check conf/nacos.yml")
            return false
        }

        // 4. 节点注册 + ActorSystem（artery 地址进 Nacos metadata）
        val actorName = when (kind) {
            NodeKind.gm -> "gmActor"
            NodeKind.gateway -> "gatewayActor"
            NodeKind.logic -> "logicServerActor"
            NodeKind.battle -> "battleServerActor"
            NodeKind.chat -> "chatServerActor"
        }
        if (!NodeRegister.start(kind, requestedId, actorName)) {
            System.err.println("node register fail")
            return false
        }

        // 启动akka
        NodeRegister.startActorSystem()

        // 5. Redis
        if (!Redis.init()) {
            System.err.println("redis connect fail, check mongo/redis config in nacos")
            return false
        }

        // 6. MongoDB
        if (!Mongo.init()) {
            System.err.println("mongo connect fail")
            return false
        }

        // 7. 业务启动（各模块 XxxStart）
        val ok = when (kind) {
            NodeKind.gm -> com.jacey.game.gm.GmStart.startBusiness()
            NodeKind.gateway -> com.jacey.game.gateway.GatewayStart.startBusiness()
            NodeKind.logic -> com.jacey.game.logic.LogicStart.startBusiness()
            NodeKind.battle -> com.jacey.game.battle.BattleStart.startBusiness()
            NodeKind.chat -> com.jacey.game.chat.ChatStart.startBusiness()
        }
        if (!ok) {
            System.err.println("business start fail")
            return false
        }

        // 8. 退出钩子：注销 Nacos 实例
        Exit.addExitListener { }
        return true
    }
}
