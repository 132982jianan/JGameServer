package com.jacey.game.logic.service

import akka.actor.ActorRef

/** 各节点 actor 引用（跨 actor 转发时使用） */
object ActorRefManagerService {
    var logicServerActor: ActorRef? = null
    var loginActor: ActorRef? = null
    var registActor: ActorRef? = null
    var matchActor: ActorRef? = null
}