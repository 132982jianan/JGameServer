package com.jacey.game.battle.service

import akka.actor.ActorRef

object BattleAkkaRefManagerService {
    var battleServerActor: ActorRef? = null
    var battleActionActor: ActorRef? = null
}