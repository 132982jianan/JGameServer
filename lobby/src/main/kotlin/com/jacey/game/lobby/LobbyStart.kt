package com.jacey.game.lobby

import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.lobby.actor.LobbyRootActor

object LobbyStart {
    suspend fun startBusiness(): Boolean {
        NacosService.subscribeByNodeKind(NodeKind.lobby)
        NacosService.subscribeByNodeKind(NodeKind.global)

        AkkaService.create<LobbyRootActor>(NodeKind.lobby.actorName)
        return true
    }
}
