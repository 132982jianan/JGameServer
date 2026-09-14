package com.jacey.game.common.akka

import com.jacey.game.common.msg.AbstractMessage
import com.jacey.game.common.msg.InternalMessageId
import kotlinx.coroutines.Job

/** 本地定时请求；与 code 的 ActorTimer 一样，通过自动注册的处理器执行回调。 */
internal class ActorTimer(
    private val owner: Job,
    private val action: suspend () -> Unit,
) : AbstractMessage() {
    init {
        msgId = InternalMessageId.ACTOR_TIMER
    }

    suspend fun process(currentOwner: Job) {
        if (owner === currentOwner && currentOwner.isActive) {
            action()
        }
    }
}
