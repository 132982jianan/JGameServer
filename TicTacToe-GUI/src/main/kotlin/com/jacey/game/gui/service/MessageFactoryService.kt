package com.jacey.game.gui.service

import com.jacey.game.common.proto3.Rpc
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * GUI 客户端消息注册（原各 actor 的响应注册汇总）
 * 在 main 中调用一次完成全部 rpcNum -> handler 绑定。
 */
object MessageFactoryService {
    private val logger = KotlinLogging.logger {}

    fun init() {
        MessageRouterService.register(Rpc.RpcNameEnum.Login_VALUE) { msg -> MessageRouterService.onLoginResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.Regist_VALUE) { msg -> MessageRouterService.onRegistResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.Match_VALUE) { msg -> MessageRouterService.onMatchResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.CancelMatch_VALUE) { msg -> MessageRouterService.onCancelMatchResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.GetBattleInfo_VALUE) { msg -> MessageRouterService.onGetBattleInfoResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.PlacePieces_VALUE) { msg -> MessageRouterService.onPlacePiecesResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.Concede_VALUE) { msg -> MessageRouterService.onConcedeResponse(msg) }
        MessageRouterService.register(Rpc.RpcNameEnum.ReadyToStartGame_VALUE) { msg ->
            MessageRouterService.onReadyToStartGameResponse(
                msg
            )
        }
        MessageRouterService.register(Rpc.RpcNameEnum.BattleChatText_VALUE) { msg -> MessageRouterService.onBattleChatTextResponse(msg) }
        MessageRouterService.register(23001) { msg -> MessageRouterService.onBattleChatTextPush(msg) } // RpcBattleChatTextPush
        MessageRouterService.register(22001) { msg -> MessageRouterService.onEventMsgListPush(msg) } // RpcBattleEventMsgListPush
        MessageRouterService.register(21001) { msg -> MessageRouterService.onMatchResultPush(msg) } // RpcMatchResultPush
        MessageRouterService.register(Rpc.RpcNameEnum.JoinChatRoom_VALUE) { msg -> MessageRouterService.onJoinChatRoomResponse(msg) }
        logger.info { "GUI message handlers registered" }
    }
}
