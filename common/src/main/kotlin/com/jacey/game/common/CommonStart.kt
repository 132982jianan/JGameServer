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

        //TODO
        Exit.listenSignal()

        //TODO
        if (!Nacos.init()) {
            System.err.println("nacos connect fail, check conf/nacos.yml")
            return false
        }

        //TODO
        if (!NacosService.start(kind, nodeId, kind.actorName)) {
            System.err.println("node register fail")
            return false
        }

        //TODO
        NacosService.startActorSystem()

        //TODO
        Exit.addExitListener {
            AkkaService.close()
        }

        //TODO
        if (!NacosService.subscribeAllNodeKinds()) {
            System.err.println("node discovery subscribe fail")
            return false
        }

        //TODO
        if (!Redis.init()) {
            System.err.println("redis connect fail, check redis config in nacos")
            return false
        }

        //TODO
        if (!Mongo.init()) {
            System.err.println("mongo connect fail")
            return false
        }

        //TODO
        Db.start()

        return true
    }
}
