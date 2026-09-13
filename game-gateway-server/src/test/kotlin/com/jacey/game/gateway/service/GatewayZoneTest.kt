package com.jacey.game.gateway.service

import com.jacey.game.common.proto3.Rpc
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 网关分区路由映射验证：
 * 每个客户端可请求的 rpcNum 必须落入正确分区（新增消息若漏配分区，此处 fail）。
 */
class GatewayZoneTest {

    @Test
    fun `认证消息落入 AUTH 分区`() {
        assertEquals(GatewayZone.AUTH, GatewayZone.of(Rpc.RpcNameEnum.Regist_VALUE))
        assertEquals(GatewayZone.AUTH, GatewayZone.of(Rpc.RpcNameEnum.Login_VALUE))
    }

    @Test
    fun `逻辑消息落入 LOGIC 分区`() {
        assertEquals(GatewayZone.LOGIC, GatewayZone.of(Rpc.RpcNameEnum.Match_VALUE))
        assertEquals(GatewayZone.LOGIC, GatewayZone.of(Rpc.RpcNameEnum.CancelMatch_VALUE))
    }

    @Test
    fun `对战消息落入 BATTLE 分区`() {
        listOf(
            Rpc.RpcNameEnum.GetBattleInfo_VALUE,
            Rpc.RpcNameEnum.Concede_VALUE,
            Rpc.RpcNameEnum.PlacePieces_VALUE,
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE,
        ).forEach { id ->
            assertEquals(GatewayZone.BATTLE, GatewayZone.of(id), "rpcNum=$id 应为 BATTLE")
        }
    }

    @Test
    fun `聊天消息落入 CHAT 分区`() {
        assertEquals(GatewayZone.CHAT, GatewayZone.of(Rpc.RpcNameEnum.JoinChatRoom_VALUE))
        assertEquals(GatewayZone.CHAT, GatewayZone.of(Rpc.RpcNameEnum.BattleChatText_VALUE))
    }

    @Test
    fun `推送号段与未知号不可由客户端请求`() {
        listOf(
            Rpc.RpcNameEnum.RpcForceOfflinePush_VALUE,
            Rpc.RpcNameEnum.RpcMatchResultPush_VALUE,
            Rpc.RpcNameEnum.RpcBattleEventMsgListPush_VALUE,
            Rpc.RpcNameEnum.RpcBattleChatTextPush_VALUE,
            Rpc.RpcNameEnum.NoneRpc_VALUE,
        ).forEach { id ->
            assertEquals(null, GatewayZone.of(id), "rpcNum=$id 不应有分区")
        }
    }

    @Test
    fun `区间边界不重叠不越界`() {
        // 分区下界
        assertEquals(GatewayZone.AUTH, GatewayZone.of(100))
        assertEquals(GatewayZone.LOGIC, GatewayZone.of(110))
        assertEquals(GatewayZone.BATTLE, GatewayZone.of(6000))
        assertEquals(GatewayZone.CHAT, GatewayZone.of(10001))
        // 分区之间的空洞（区间为闭区间，上界属于本分区）
        assertEquals(GatewayZone.AUTH, GatewayZone.of(109))
        assertEquals(null, GatewayZone.of(99))
        assertEquals(null, GatewayZone.of(200))
        assertEquals(null, GatewayZone.of(5999))
        assertEquals(null, GatewayZone.of(7000))
        assertEquals(null, GatewayZone.of(14001))
    }
}
