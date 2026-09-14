package com.jacey.game.battle

import com.jacey.game.battle.actor.BattleServerActor
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService

/**
 * 对战服启动器：组装 actors + Nacos 注册
 */
object BattleStart {
    suspend fun startBusiness(): Boolean {
        NacosService.subscribeByNodeKind(NodeKind.global)
        AkkaService.create<BattleServerActor>(NodeKind.battle.actorName)
        return true
    }
}

