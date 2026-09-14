package com.jacey.game.server

import com.jacey.game.battle.BattleStart
import com.jacey.game.global.GlobalStart
import com.jacey.game.gate.GateStart
import com.jacey.game.common.framework.nacos.Nacos
import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.common.framework.process.Log4j2
import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.redis.Redis
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.db.Db
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.insight.InsightStart
import com.jacey.game.lobby.LobbyStart
import com.jacey.game.portal.PortalStart

/**
 * 各节点通用启动序列（替代原 Spring Boot 启动 + CoreManager）
 *
 * 顺序：日志 → 信号 → Nacos → 节点注册(含 Akka) → 按需存储 → 业务启动(actors/HTTP/Netty)
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
        if (!NacosService.start(kind, requestedId, kind.actorName)) {
            System.err.println("node register fail")
            return false
        }

        // 启动akka
        NacosService.startActorSystem()
        Exit.addExitListener { AkkaService.close() }

        // Redis 仅用于 Insight 的短期令牌；在线/匹配/战斗运行态全部在 ActorState。
        if (kind == NodeKind.insight) {
            if (!Redis.init()) {
                System.err.println("redis connect fail, check mongo/redis config in nacos")
                return false
            }
        }

        if (kind == NodeKind.lobby || kind == NodeKind.battle || kind == NodeKind.insight) {
            // 6. MongoDB + 统一集合入口
            if (!Mongo.init()) {
                System.err.println("mongo connect fail")
                return false
            }
            Db.start()
        }

        // 7. 业务启动（各模块 XxxStart）
        val success = when (kind) {
            NodeKind.portal -> PortalStart.startBusiness()
            NodeKind.gate -> GateStart.startBusiness()
            NodeKind.lobby -> LobbyStart.startBusiness()
            NodeKind.global -> GlobalStart.startBusiness()
            NodeKind.battle -> BattleStart.startBusiness()
            NodeKind.insight -> InsightStart.startBusiness()
        }

        if (!success) {
            System.err.println("business start fail")
            return false
        }

        return true
    }
}
