package com.jacey.game.gateway.service

import com.jacey.game.common.proto3.Rpc
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 网关分区路由映射验证：
 * 每个客户端可请求的 msgId 必须落入正确分区（新增消息若漏配分区，此处 fail）。
 */
class GatewayZoneTest {

    @Test
    fun `认证消息落入 AUTH 分区`() {
        assertEquals(EGatewayZone.AUTH, EGatewayZone.calEGatewayZoneByMsgId(Rpc.RpcNameEnum.Regist_VALUE))
        assertEquals(EGatewayZone.AUTH, EGatewayZone.calEGatewayZoneByMsgId(Rpc.RpcNameEnum.Login_VALUE))
    }

    @Test
    fun `逻辑消息落入 LOGIC 分区`() {
        assertEquals(EGatewayZone.LOGIC, EGatewayZone.calEGatewayZoneByMsgId(Rpc.RpcNameEnum.Match_VALUE))
        assertEquals(EGatewayZone.LOGIC, EGatewayZone.calEGatewayZoneByMsgId(Rpc.RpcNameEnum.CancelMatch_VALUE))
    }

    @Test
    fun `对战消息落入 BATTLE 分区`() {
        listOf(
            Rpc.RpcNameEnum.GetBattleInfo_VALUE,
            Rpc.RpcNameEnum.Concede_VALUE,
            Rpc.RpcNameEnum.PlacePieces_VALUE,
            Rpc.RpcNameEnum.ReadyToStartGame_VALUE,
        ).forEach { id ->
            assertEquals(EGatewayZone.BATTLE, EGatewayZone.calEGatewayZoneByMsgId(id), "msgId=$id 应为 BATTLE")
        }
    }

    @Test
    fun `聊天消息落入 CHAT 分区`() {
        assertEquals(EGatewayZone.CHAT, EGatewayZone.calEGatewayZoneByMsgId(Rpc.RpcNameEnum.JoinChatRoom_VALUE))
        assertEquals(EGatewayZone.CHAT, EGatewayZone.calEGatewayZoneByMsgId(Rpc.RpcNameEnum.BattleChatText_VALUE))
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
            assertEquals(null, EGatewayZone.calEGatewayZoneByMsgId(id), "msgId=$id 不应有分区")
        }
    }

    @Test
    fun `区间边界不重叠不越界`() {
        // 分区下界
        assertEquals(EGatewayZone.AUTH, EGatewayZone.calEGatewayZoneByMsgId(100))
        assertEquals(EGatewayZone.LOGIC, EGatewayZone.calEGatewayZoneByMsgId(110))
        assertEquals(EGatewayZone.BATTLE, EGatewayZone.calEGatewayZoneByMsgId(6000))
        assertEquals(EGatewayZone.CHAT, EGatewayZone.calEGatewayZoneByMsgId(10001))
        // 分区之间的空洞（区间为闭区间，上界属于本分区）
        assertEquals(EGatewayZone.AUTH, EGatewayZone.calEGatewayZoneByMsgId(109))
        assertEquals(null, EGatewayZone.calEGatewayZoneByMsgId(99))
        assertEquals(null, EGatewayZone.calEGatewayZoneByMsgId(200))
        assertEquals(null, EGatewayZone.calEGatewayZoneByMsgId(5999))
        assertEquals(null, EGatewayZone.calEGatewayZoneByMsgId(7000))
        assertEquals(null, EGatewayZone.calEGatewayZoneByMsgId(14001))
    }
}
