package com.jacey.game.chat

import com.jacey.game.chat.actor.ChatServerActor
import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService

/**
 * 聊天服启动器
 */
object ChatStart {
    suspend fun startBusiness(): Boolean {
        NacosService.subscribeByNodeKind(NodeKind.battle)

        AkkaService.create<ChatServerActor>(NodeKind.chat.actorName)

        return true
    }
}
