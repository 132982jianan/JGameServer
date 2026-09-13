package com.jacey.game.chat

import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister

/**
 * 聊天服启动器
 */
object ChatStart {
    suspend fun startBusiness(): Boolean {
        NodeRegister.subscribe(NodeKind.gm)
        NodeRegister.subscribe(NodeKind.battle)
        AkkaService.create<ChatServerActor>("chatServerActor")
        return true
    }
}
