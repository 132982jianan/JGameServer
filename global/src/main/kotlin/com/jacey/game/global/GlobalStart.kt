package com.jacey.game.global

import com.jacey.game.common.CommonStart
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.global.actor.GlobalRootActor

object GlobalStart {
    suspend fun start(nodeId: NodeId?): Boolean {
        if (!CommonStart.start(NodeKind.global, nodeId)) return false
        NacosService.subscribeByNodeKind(NodeKind.battle)
        AkkaService.create<GlobalRootActor>(NodeKind.global.actorName)
        return true
    }
}
