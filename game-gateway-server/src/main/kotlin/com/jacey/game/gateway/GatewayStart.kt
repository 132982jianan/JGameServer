package com.jacey.game.gateway

import com.jacey.game.common.framework.akka.AkkaService
import com.jacey.game.gateway.actor.GatewayNodeActor
import com.jacey.game.gateway.network.NettyServer

/** 网关业务启动（Nacos 注册与 ActorSystem 已由 CommonStart 完成） */
object GatewayStart {
    suspend fun startBusiness(): Boolean {
        // 创建网关主 actor
        AkkaService.create<GatewayNodeActor>("gatewayActor")
        // 启动 Netty TCP/WebSocket
        NettyServer.start()
        return true
    }
}
