package com.jacey.game.battle

import com.jacey.game.common.CommonStart
import com.jacey.game.battle.actor.BattleServerActor
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeId

/**
 * 对战服启动器：组装 actors + Nacos 注册
 */
object BattleStart {
    suspend fun start(nodeId: NodeId?): Boolean {
        if (!CommonStart.start(NodeKind.battle, nodeId)) return false
        AkkaService.create<BattleServerActor>(NodeKind.battle.actorName)
        return true
    }
}

