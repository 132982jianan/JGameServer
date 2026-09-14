package com.jacey.game.lobby

import com.jacey.game.common.CommonStart
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeId
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.lobby.actor.LobbyRootActor

object LobbyStart {
    suspend fun start(nodeId: NodeId?): Boolean {
        if (!CommonStart.start(NodeKind.lobby, nodeId)) return false

        AkkaService.create<LobbyRootActor>(NodeKind.lobby.actorName)
        return true
    }
}
