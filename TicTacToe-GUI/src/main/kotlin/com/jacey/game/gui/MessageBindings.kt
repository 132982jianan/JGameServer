package com.jacey.game.gui

import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.SwingUtilities

/**
 * GUI 客户端消息注册（原各 actor 的响应注册汇总）
 * 在 main 中调用一次完成全部 rpcNum -> handler 绑定。
 */
object MessageBindings {
    private val logger = KotlinLogging.logger {}

    fun registerAll() {
        MessageRouter.register(Rpc.RpcNameEnum.Login_VALUE) { msg -> MessageRouter.onLoginResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.Regist_VALUE) { msg -> MessageRouter.onRegistResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.Match_VALUE) { msg -> MessageRouter.onMatchResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.CancelMatch_VALUE) { msg -> MessageRouter.onCancelMatchResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.GetBattleInfo_VALUE) { msg -> MessageRouter.onGetBattleInfoResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.PlacePieces_VALUE) { msg -> MessageRouter.onPlacePiecesResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.Concede_VALUE) { msg -> MessageRouter.onConcedeResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.ReadyToStartGame_VALUE) { msg -> MessageRouter.onReadyToStartGameResponse(msg) }
        MessageRouter.register(Rpc.RpcNameEnum.BattleChatText_VALUE) { msg -> MessageRouter.onBattleChatTextResponse(msg) }
        MessageRouter.register(23001) { msg -> MessageRouter.onBattleChatTextPush(msg) } // RpcBattleChatTextPush
        MessageRouter.register(22001) { msg -> MessageRouter.onEventMsgListPush(msg) } // RpcBattleEventMsgListPush
        MessageRouter.register(20001) { msg -> MessageRouter.onForceOfflinePush(msg) } // RpcForceOfflinePush
        MessageRouter.register(Rpc.RpcNameEnum.JoinChatRoom_VALUE) { msg -> MessageRouter.onJoinChatRoomResponse(msg) }
        logger.info { "GUI message handlers registered" }
    }
}
