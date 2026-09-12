package com.jacey.game.logic

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.db.redis.SessionIdRedis

/**
 * 系统参数表（原 SystemConfig.xlsx 固化为代码常量，无需配置文件）
 *
 * 原表内容：
 * usernameMaxLength=18 用户名最大长度
 * passwordMinLength=8  密码最小长度
 * passwordMaxLength=18 密码最大长度
 * nicknameMaxLength=8  昵称最大长度
 */
object TableConfig {
    private val configs = mapOf(
        "usernameMaxLength" to "18",
        "passwordMinLength" to "8",
        "passwordMaxLength" to "18",
        "nicknameMaxLength" to "8",
    )

    fun systemInt(key: String): Int? = configs[key]?.toIntOrNull()
    fun systemString(key: String): String? = configs[key]
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
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.LogicServerActor>("logicServerActor")
    fun createLoginActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.LoginActor>("loginActor")
    fun createRegistActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.RegistActor>("registActor")
    fun createMatchActor() =
        com.jacey.game.common.framework.akka.Akka.create<com.jacey.game.logic.MatchActor>("matchActor")
}
