package com.jacey.game.common

import com.jacey.game.common.db.Db
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.mongo.Mongo
import com.jacey.game.common.framework.nacos.Nacos
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.process.Exit
import com.jacey.game.common.framework.process.Log4j2
import com.jacey.game.common.framework.redis.Redis

/**
 * 所有节点共享的基础启动序列。
 *
 * 与 reference code 一致，本类只初始化公共设施；具体业务由各 XxxStart.start(nodeId) 负责。
 */
object CommonStart {
    suspend fun start(kind: NodeKind, nodeId: NodeId?): Boolean {
        Log4j2.init(kind.name)
        Exit.listenSignal()

        if (!Nacos.init()) {
            System.err.println("nacos connect fail, check conf/nacos.yml")
            return false
        }
        if (!NacosService.start(kind, nodeId, kind.actorName)) {
            System.err.println("node register fail")
            return false
        }

        NacosService.startActorSystem()
        Exit.addExitListener { AkkaService.close() }

        // Redis 只用于 Insight 的短期令牌；在线、匹配和战斗运行态均在 ActorState。
        if (kind == NodeKind.insight && !Redis.init()) {
            System.err.println("redis connect fail, check redis config in nacos")
            return false
        }

        if (kind in setOf(NodeKind.lobby, NodeKind.battle, NodeKind.insight)) {
            if (!Mongo.init()) {
                System.err.println("mongo connect fail")
                return false
            }
            Db.start()
        }
        return true
    }
}
