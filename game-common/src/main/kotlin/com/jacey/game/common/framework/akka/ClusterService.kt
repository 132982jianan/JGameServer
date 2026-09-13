package com.jacey.game.common.framework.akka

import akka.actor.ActorRef
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.RemoteMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 跨节点 RPC 统一入口：NodeKind + nodeId 即可通信，不暴露 ActorRef 寻址细节。
 *
 * - [tell]   单向通知/推送，不等待
 * - [askAwait] 请求-响应：同步写法、非阻塞等待（挂起直到对端回复或超时）
 *
 * 寻址唯一路径：NacosService.getActorRefByNodeKindAndNodeId（Nacos 目录 + ActorRef 缓存）。
 * 返回 false / null 表示目标节点不在线。
 */
object ClusterService {
    private val logger = KotlinLogging.logger {}

    /** 单向发送（fire-and-forget）。返回 false = 节点不在线（已记日志） */
    suspend fun tell(kind: NodeKind, nodeId: Int, msg: RemoteMessage): Boolean {
        val ref = NacosService.getActorRefByNodeKindAndNodeId(kind, nodeId) ?: run {
            logger.error { "【RPC失败】节点不在线 kind=$kind nodeId=$nodeId msgId=${msg.msgId}" }
            return false
        }
        ref.tell(msg, ActorRef.noSender())
        return true
    }

    /**
     * 请求-响应：挂起等待对端回复（非阻塞），超时抛异常。
     * 对端按惯例回 RemoteMessage：errorCode == RemoteRpcOk 视为成功。
     * 返回 null = 节点不在线；成功返回响应（errorCode 非 Ok 由调用方判或抛）。
     */
    suspend fun askAwait(
        kind: NodeKind,
        nodeId: Int,
        msg: RemoteMessage,
        timeout: Duration = 5.seconds,
    ): RemoteMessage? {
        val ref = NacosService.getActorRefByNodeKindAndNodeId(kind, nodeId) ?: run {
            logger.error { "【RPC失败】节点不在线 kind=$kind nodeId=$nodeId msgId=${msg.msgId}" }
            return null
        }
        val reply = ref.askAwait(msg, timeout)
        return reply as? RemoteMessage
    }

    /** askAwait + 随机在线节点（无固定目标的负载均衡调用，如"任一 battle"） */
    suspend fun askRandomAwait(
        kind: NodeKind,
        msg: RemoteMessage,
        timeout: Duration = 5.seconds,
    ): RemoteMessage? {
        val info = NacosService.getNodeInfoListByNodeKind(kind).randomOrNull() ?: run {
            logger.error { "【RPC失败】无在线节点 kind=$kind msgId=${msg.msgId}" }
            return null
        }
        return askAwait(kind, info.nodeId, msg, timeout)
    }
}
