package com.jacey.game.battle

import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister

/**
 * 对战服启动器：组装 actors + Nacos 注册
 */
object BattleStart {
    suspend fun startBusiness(): Boolean {
        NodeRegister.subscribe(NodeKind.gm)
        NodeRegister.subscribe(NodeKind.chat)
        AkkaRefsB.battleServerActor = AkkaService.create<BattleServerActor>(NodeKind.battle.actorName)
        AkkaRefsB.battleActionActor = AkkaService.create<BattleActionActor>("battleActionActor")
        return true
    }
}

object AkkaRefsB {
    var battleServerActor: akka.actor.ActorRef? = null
    var battleActionActor: akka.actor.ActorRef? = null
}
