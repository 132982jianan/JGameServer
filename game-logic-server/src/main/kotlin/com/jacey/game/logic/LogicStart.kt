package com.jacey.game.logic

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.db.redis.SessionIdRedis

/** TableConfig：SystemConfig.xlsx 参数表（object 单例，原 TableConfigManager） */
object TableConfig {
    private val cache = HashMap<String, String>()

    @Synchronized
    fun load(entries: Map<String, String>) {
        cache.clear()
        cache.putAll(entries)
    }

    fun systemInt(key: String): Int? = cache[key]?.toIntOrNull()
    fun systemString(key: String): String? = cache[key]
}

/**
 * 逻辑服启动器（object）：组装各 actor 并启动
 */
object LogicStart {
    suspend fun startBusiness(): Boolean {
        val conf = AppConfig.instance
        // 订阅其它节点
        NodeRegister.subscribe(NodeKind.gm)
        NodeRegister.subscribe(NodeKind.battle)
        NodeRegister.subscribe(NodeKind.chat)
        // 创建 actors
        AkkaRefs.logicServerActor = AkkaCreates.createLogicServerActor()
        AkkaRefs.loginActor = AkkaCreates.createLoginActor()
        if (conf.isMainLogicServer) {
            AkkaCreates.createRegistActor()
            AkkaRefs.matchActor = AkkaCreates.createMatchActor()
        }
        return true
    }
}

/** 各节点 actor 引用（跨 actor 转发时使用） */
object AkkaRefs {
    var logicServerActor: akka.actor.ActorRef? = null
    var loginActor: akka.actor.ActorRef? = null
    var registActor: akka.actor.ActorRef? = null
    var matchActor: akka.actor.ActorRef? = null
}

/** actor 创建封装（便于 gateway/logic 各自注入子类） */
object AkkaCreates {
    fun createLogicServerActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.LogicServerActor>("logicServerActor")
    fun createLoginActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.LoginActor>("loginActor")
    fun createRegistActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.RegistActor>("registActor")
    fun createMatchActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.MatchActor>("matchActor")
}
