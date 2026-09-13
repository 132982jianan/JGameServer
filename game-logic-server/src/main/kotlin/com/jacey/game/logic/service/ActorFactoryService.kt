package com.jacey.game.logic.service

import akka.actor.ActorRef
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.logic.actor.LogicServerActor
import com.jacey.game.logic.actor.LoginActor
import com.jacey.game.logic.actor.MatchActor
import com.jacey.game.logic.actor.RegistActor

/** actor 创建封装（便于 gateway/logic 各自注入子类） */
object ActorFactoryService {
    fun createLogicServerActor(): ActorRef {
        return AkkaService.create<LogicServerActor>(NodeKind.logic.actorName)
    }

    fun createLoginActor(): ActorRef {
        return AkkaService.create<LoginActor>("loginActor")
    }

    fun createRegistActor(): ActorRef {
        return AkkaService.create<RegistActor>("registActor")
    }

    fun createMatchActor(): ActorRef {
        return AkkaService.create<MatchActor>("matchActor")
    }
}