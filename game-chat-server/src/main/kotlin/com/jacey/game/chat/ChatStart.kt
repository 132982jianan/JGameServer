package com.jacey.game.chat

import com.jacey.game.common.framework.config.AppConfig
import com.jacey.game.common.framework.akka.Akka
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister

/**
 * 聊天服启动器
 */
object ChatStart {
    suspend fun startBusiness(): Boolean {
        NodeRegister.subscribe(NodeKind.gm)
        NodeRegister.subscribe(NodeKind.battle)
        Akka.create<ChatServerActor>("chatServerActor")
        Akka.create<ChatRoomManagerProxy>("chatRoomMangerActor")
        return true
    }
}
