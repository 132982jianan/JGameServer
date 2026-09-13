package com.jacey.game.common.framework.akka

import akka.actor.ActorRef

/** 消息信封：附带发送者引用，供 onMessage 内 reply 使用 */
data class Envelope(val msg: Any, val sender: ActorRef?)