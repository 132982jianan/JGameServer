package com.jacey.game.battle

import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.msg.RemoteMessage
import io.github.oshai.kotlinlogging.KotlinLogging

/** battle 侧 GM 连接状态与 GM 路由 */
object MessageRouterB {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var isConnectedToGm: Boolean = false

    suspend fun sendRemoteToGm(msg: RemoteMessage, sender: akka.actor.ActorRef?) {
        val ref = NacosService.getActorRefByNodeKindAndNodeId(NodeKind.gm, 1)
        ref?.tell(msg, sender) ?: logger.error { "gm actor not found" }
    }
}
