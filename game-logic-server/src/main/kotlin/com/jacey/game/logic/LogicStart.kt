package com.jacey.game.logic

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister

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
            AkkaRefs.registActor = AkkaCreates.createRegistActor()
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
        com.jacey.game.common.framework.akka.AkkaService.create<com.jacey.game.logic.LogicServerActor>(NodeKind.logic.actorName)
    fun createLoginActor() =
        com.jacey.game.common.framework.akka.AkkaService.create<com.jacey.game.logic.LoginActor>("loginActor")
    fun createRegistActor() =
        com.jacey.game.common.framework.akka.AkkaService.create<com.jacey.game.logic.RegistActor>("registActor")
    fun createMatchActor() =
        com.jacey.game.common.framework.akka.AkkaService.create<com.jacey.game.logic.MatchActor>("matchActor")
}
