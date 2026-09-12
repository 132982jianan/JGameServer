package com.jacey.game.gateway

import akka.actor.ActorRef
import com.jacey.game.common.framework.akka.Akka
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister
import com.jacey.game.gateway.actor.GatewayNodeActor
import com.jacey.game.gateway.network.NettyServer

/** 网关业务启动（Nacos 注册与 ActorSystem 已由 CommonStart 完成） */
object GatewayStart {
    suspend fun startBusiness(): Boolean {
        // 创建网关主 actor
        Akka.create<GatewayNodeActor>("gatewayActor")
        // 启动 Netty TCP/WebSocket
        NettyServer.start()
        return true
    }
}
