package com.jacey.game.global

import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.global.actor.GlobalRootActor

object GlobalStart {
    suspend fun startBusiness(): Boolean {
        NacosService.subscribeByNodeKind(NodeKind.battle)
        AkkaService.create<GlobalRootActor>(NodeKind.global.actorName)
        return true
    }
}
