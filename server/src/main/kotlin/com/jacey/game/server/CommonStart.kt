package com.jacey.game.server

import com.jacey.game.battle.BattleStart
import com.jacey.game.chat.ChatStart
import com.jacey.game.common.framework.nacos.Nacos
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.common.framework.process.Log4j2
import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.redis.Redis
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.gateway.GatewayStart
import com.jacey.game.gm.GmStart
import com.jacey.game.logic.LogicStart

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

        // 4. 节点注册 + ActorSystem（artery 地址/对外端口进 Nacos metadata）
        val connectPath = if (kind == NodeKind.gateway) AppConfig.instance.gatewayConnectPath else ""
        val isMainLogic = kind == NodeKind.logic && AppConfig.instance.isMainLogicServer
        if (!NacosService.start(kind, requestedId, kind.actorName, connectPath, isMainLogic)) {
            System.err.println("node register fail")
            return false
        }

        // 启动akka
        NacosService.startActorSystem()

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
        val success = when (kind) {
            NodeKind.gm -> GmStart.startBusiness()
            NodeKind.gateway -> GatewayStart.startBusiness()
            NodeKind.logic -> LogicStart.startBusiness()
            NodeKind.battle -> BattleStart.startBusiness()
            NodeKind.chat -> ChatStart.startBusiness()
        }

        if (!success) {
            System.err.println("business start fail")
            return false
        }

        // 8. 退出钩子：注销 Nacos 实例
        Exit.addExitListener { }
        return true
    }
}
