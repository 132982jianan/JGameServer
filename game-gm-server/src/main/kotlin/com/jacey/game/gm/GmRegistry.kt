package com.jacey.game.gm

import akka.actor.ActorRef
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.db.service.BattleInfoService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * GM 服务器节点注册表（object 单例，原 gm MessageManager 的服务器注册部分）
 *
 * 各服务器通过 akka remote 发送 RegistServerRequest 注册：
 * - 记录 actor 引用（Terminated 时移除）
 * - 连接路径 (connectPath) 与负载通过 Redis 共享（供 ClientController 查询）
 */
object GmRegistry {
    private val logger = KotlinLogging.logger {}

    // nodeId -> actorRef（各类型节点）
    private val logicActors = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()
    private val battleActors = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()
    private val chatActors = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()
    private val gatewayActors = java.util.concurrent.ConcurrentHashMap<Int, ActorRef>()

    /** 主逻辑服务器 id（第一个声明 main 的 logic） */
    @Volatile
    var mainLogicServerId: Int = 0
        private set

    /** 处理服务器注册（由 GmActor 调用） */
    suspend fun registServer(request: RemoteServer.RegistServerRequest, sender: ActorRef?, self: ActorRef): Boolean {
        val serverInfo = request.serverInfo
        val serverType = serverInfo.serverType
        val serverId = serverInfo.serverId
        return when (serverType) {
            CommonEnum.RemoteServerTypeEnum.ServerTypeLogic -> {
                if (logicActors.containsKey(serverId)) false
                else {
                    if (serverInfo.isMainLogicServer) {
                        if (mainLogicServerId > 0) return false
                        mainLogicServerId = serverId
                        // mainLogicServerId 存 Redis（原 mainLogicServerId 键，兼容客户端路由）
                    }
                    logicActors[serverId] = sender ?: return false
                    true
                }
            }
            CommonEnum.RemoteServerTypeEnum.ServerTypeBattle -> {
                if (battleActors.containsKey(serverId)) false
                else {
                    battleActors[serverId] = sender ?: return false
                    true
                }
            }
            CommonEnum.RemoteServerTypeEnum.ServerTypeChat -> {
                if (chatActors.containsKey(serverId)) false
                else {
                    chatActors[serverId] = sender ?: return false
                    true
                }
            }
            CommonEnum.RemoteServerTypeEnum.ServerTypeGateway -> {
                if (gatewayActors.containsKey(serverId)) false
                else {
                    gatewayActors[serverId] = sender ?: return false
                    // 网关连接地址存 Redis（原 gatewayIdToConnectPath，客户端经 GM HTTP 获取）
                    BattleInfoService.setOneGatewayConnectPath(serverId, serverInfo.gatewayConnectPath)
                    true
                }
            }
            else -> {
                logger.error { "registServer: unknown serverType=$serverType" }
                false
            }
        }
    }

    /** 节点下线移除 */
    fun removeActor(actor: ActorRef) {
        logicActors.values.remove(actor)
        battleActors.values.remove(actor)
        chatActors.values.remove(actor)
        gatewayActors.values.remove(actor)
    }

    /** 获取最空闲网关（原 getLeisureGatewayId 语义：Nacos 负载 + connectPath） */
    suspend fun getLeisureGatewayConnectPath(): String? {
        val gateways = NacosService.nodesOf(NodeKind.gateway)
        if (gateways.isEmpty()) return null
        // 简单策略：取 id 最小的在线网关（原版为 zset 负载排序；Nacos 已含健康检查）
        val gateway = gateways.minByOrNull { it.nodeId } ?: return null
        return BattleInfoService.getOneGatewayConnectPath(gateway.nodeId)
    }
}
