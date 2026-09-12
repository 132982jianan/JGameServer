package com.jacey.game.gui

import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.BaseBattle
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import javax.swing.JOptionPane

/**
 * 客户端消息路由（object 单例，原 MessageManager + 各 actor 的响应处理）
 *
 * rpcNum -> handler 显式注册；UI 更新通过 SwingUtilities.invokeLater 回到 EDT。
 */
object MessageRouter {
    private val logger = KotlinLogging.logger {}
    private val handlers = HashMap<Int, suspend (NetMessage) -> Unit>()

    fun register(rpcNum: Int, handler: suspend (NetMessage) -> Unit) {
        require(!handlers.containsKey(rpcNum)) { "duplicate handler rpcNum=$rpcNum" }
        handlers[rpcNum] = handler
    }

    /** Netty 线程收到服务器消息后调用：切到协程再分发 */
    fun dispatch(msg: NetMessage) {
        val handler = handlers[msg.rpcNum]
        if (handler == null) {
            logger.error { "【消息处理异常】不支持该协议 rpcNum=${msg.rpcNum}" }
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                handler(msg)
            } catch (e: Exception) {
                logger.error(e) { "handle msg fail rpcNum=${msg.rpcNum}" }
            }
        }
    }

    // ============ 各消息处理器（由 GuiApplication 启动时注册） ============

    /** 登录响应（原 LoginActor.loginResponse） */
    suspend fun onLoginResponse(msg: NetMessage) {
        when (msg.errorCode) {
            Rpc.RpcErrorCodeEnum.Ok_VALUE -> {
                val resp = msg.getProto<CommonMsg.LoginResponse>() ?: return
                logger.info { "【登录响应】登录成功" }
                val userInfo = resp.userInfo
                val actionState = userInfo.userState.actionState
                Session.userInfo = userInfo
                Session.isLogin = true
                when (actionState.number) {
                    CommonEnum.UserActionStateEnum.ActionNone_VALUE,
                    CommonEnum.UserActionStateEnum.Matching_VALUE -> {
                        swing {
                            Views.hallFrame = HallFrame(userInfo).also { it.isVisible = true }
                            Views.loginFrame?.dispose()
                            Views.loginFrame = null
                        }
                    }
                    CommonEnum.UserActionStateEnum.Playing_VALUE -> {
                        // 对战中登录：请求对战信息（对战界面由响应创建）
                        val builder = BaseBattle.GetBattleInfoRequest.newBuilder()
                        ServerConnection.send(NetMessage(Rpc.RpcNameEnum.GetBattleInfo_VALUE, builder))
                    }
                }
            }
            Rpc.RpcErrorCodeEnum.LoginErrorForbid_VALUE -> {
                logger.error { "【登录响应】无法登录，账号被封禁" }
                swingDialog("Unable to login, the user has been banned")
            }
            Rpc.RpcErrorCodeEnum.LoginErrorUsernameIsNotExist_VALUE -> {
                logger.error { "【登录响应】用户名不存在" }
                swingDialog("Username does not exist")
            }
            Rpc.RpcErrorCodeEnum.LoginErrorPasswordWrong_VALUE -> {
                logger.error { "【登录响应】密码错误" }
                swingDialog("Wrong password")
            }
            else -> {
                logger.error { "【登录响应】未知错误 errorCode=${msg.errorCode}" }
                swingDialog("Login failed (code=${msg.errorCode})")
            }
        }
    }

    /** 注册响应（原 RegistActor 响应路径） */
    suspend fun onRegistResponse(msg: NetMessage) {
        if (msg.errorCode == Rpc.RpcErrorCodeEnum.Ok_VALUE) {
            logger.info { "【注册响应】注册成功，请登录" }
            swingDialog("Register success, please login")
        } else {
            val text = when (msg.errorCode) {
                Rpc.RpcErrorCodeEnum.RegisErrorUsernameIsExist_VALUE -> "Username already exists"
                Rpc.RpcErrorCodeEnum.RegisErrorUsernameIllegal_VALUE -> "Username illegal"
                Rpc.RpcErrorCodeEnum.RegisErrorPasswordIllegal_VALUE -> "Password illegal"
                else -> "Register failed (code=${msg.errorCode})"
            }
            logger.error { "【注册响应】$text" }
            swingDialog(text)
        }
    }

    /** 匹配响应（原 MatchActor.matchResponse） */
    suspend fun onMatchResponse(msg: NetMessage) {
        when (msg.errorCode) {
            Rpc.RpcErrorCodeEnum.Ok_VALUE -> {
                logger.info { "【匹配响应】正在匹配中..." }
                swing {
                    Views.hallFrame?.matchBtn?.isEnabled = false
                    Views.hallFrame?.unmatchBtn?.isEnabled = true
                }
            }
            Rpc.RpcErrorCodeEnum.MatchErrorMatching_VALUE -> swingDialog("Unable to match, this is currently the match status")
            Rpc.RpcErrorCodeEnum.MatchErrorPlaying_VALUE -> swingDialog("Unmatchable, already in battle")
            Rpc.RpcErrorCodeEnum.MatchErrorOtherActionState_VALUE -> swingDialog("Unable to match, in other states")
            Rpc.RpcErrorCodeEnum.ServerError_VALUE -> swingDialog("Internal server error")
            else -> swingDialog("Match failed (code=${msg.errorCode})")
        }
    }

    /** 取消匹配响应（原 CancelMatch 响应路径） */
    suspend fun onCancelMatchResponse(msg: NetMessage) {
        if (msg.errorCode == Rpc.RpcErrorCodeEnum.Ok_VALUE) {
            logger.info { "【取消匹配响应】已取消匹配" }
            swing {
                Views.hallFrame?.matchBtn?.isEnabled = true
                Views.hallFrame?.unmatchBtn?.isEnabled = false
            }
        } else {
            swingDialog("Cancel match failed (code=${msg.errorCode})")
        }
    }

    /** 对战事件列表推送（原 BattleEventMsgListPushActor + BaseBattleEventService） */
    suspend fun onEventMsgListPush(msg: NetMessage) {
        val push = msg.getProto<BaseBattle.BattleEventMsgListPush>() ?: return
        BattleEvents.doEvent(push.eventMsgList.msgListList)
    }

    /** 落子响应（原 PlacePiecesAction） */
    suspend fun onPlacePiecesResponse(msg: NetMessage) {
        when (msg.errorCode) {
            Rpc.RpcErrorCodeEnum.Ok_VALUE -> {
                val resp = msg.getProto<BaseBattle.PlacePiecesResponse>() ?: return
                BattleEvents.doEvent(resp.eventList.msgListList)
            }
            Rpc.RpcErrorCodeEnum.PlacePiecesErrorIndexError_VALUE -> swingDialog("Position failure, the position of the child is illegal")
            Rpc.RpcErrorCodeEnum.PlacePiecesErrorIndexIsNotEmpty_VALUE -> swingDialog("Position failure, There are already chess pieces")
            Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE -> swingDialog("Player is not playing")
            Rpc.RpcErrorCodeEnum.BattleNotStart_VALUE -> swingDialog("The game has not started")
            Rpc.RpcErrorCodeEnum.IsNotUserTurn_VALUE -> swingDialog("Not your player's turn")
            Rpc.RpcErrorCodeEnum.InputLastEventNumError_VALUE -> logger.error { "【落子请求响应】丢包...." }
            else -> swingDialog("Place pieces failed (code=${msg.errorCode})")
        }
    }

    /** 认输响应（原 ConcedeActor） */
    suspend fun onConcedeResponse(msg: NetMessage) {
        when (msg.errorCode) {
            Rpc.RpcErrorCodeEnum.Ok_VALUE -> {
                logger.info { "【认输响应】认输成功..." }
                val resp = msg.getProto<BaseBattle.ConcedeResponse>() ?: return
                BattleEvents.doEvent(resp.eventList.msgListList)
            }
            Rpc.RpcErrorCodeEnum.UserNotInBattle_VALUE -> swingDialog("Player is not playing")
            Rpc.RpcErrorCodeEnum.BattleNotStart_VALUE -> swingDialog("The game has not started")
            else -> swingDialog("Concede failed (code=${msg.errorCode})")
        }
    }

    /** 获取对战信息响应（原 GetBattleInfoAction：创建对战界面 + 加入聊天室） */
    suspend fun onGetBattleInfoResponse(msg: NetMessage) {
        when (msg.errorCode) {
            Rpc.RpcErrorCodeEnum.Ok_VALUE -> {
                val resp = msg.getProto<BaseBattle.GetBattleInfoResponse>() ?: return
                val battleInfo = resp.battleInfo
                swing {
                    Views.hallFrame?.dispose()
                    Views.loginFrame?.dispose()
                    Views.hallFrame = null
                    Views.battleFrame = BattleFrame(battleInfo).also { it.isVisible = true }
                }
                // 发送加入对战聊天室请求
                val builder = CommonMsg.JoinChatRoomRequest.newBuilder()
                    .setChatRoomType(CommonEnum.ChatRoomTypeEnum.TwoPlayerBattleChatRoomType)
                ServerConnection.send(NetMessage(Rpc.RpcNameEnum.JoinChatRoom_VALUE, builder))
                logger.info { "加入聊天室请求发送...." }
            }
            else -> swingDialog("Get battle info failed (code=${msg.errorCode})")
        }
    }

    /** 确认开始游戏响应（原 ReadyToStartGame 响应路径） */
    suspend fun onReadyToStartGameResponse(msg: NetMessage) {
        when (msg.errorCode) {
            Rpc.RpcErrorCodeEnum.Ok_VALUE -> logger.info { "【确认开始响应】已确认" }
            Rpc.RpcErrorCodeEnum.ReadyToStartGameErrorAlreadyReady_VALUE -> logger.error { "【确认开始响应】已经确认过了" }
            else -> swingDialog("Ready failed (code=${msg.errorCode})")
        }
    }

    /** 聊天文本发送响应（原 BattleChatText 响应路径） */
    suspend fun onBattleChatTextResponse(msg: NetMessage) {
        if (msg.errorCode != Rpc.RpcErrorCodeEnum.Ok_VALUE) {
            if (msg.errorCode == Rpc.RpcErrorCodeEnum.BattleChatTextErrorNotJoinBattle_VALUE) {
                swingDialog("Send failed: not in battle")
            } else {
                swingDialog("Send failed (code=${msg.errorCode})")
            }
        }
    }

    /** 对战聊天推送（原 ChatRoomActor.battleChatTextPush） */
    suspend fun onBattleChatTextPush(msg: NetMessage) {
        val push = msg.getProto<CommonMsg.BattleChatTextPush>() ?: return
        logger.info { "【聊天消息接收】sender=${push.senderUserId} text=${push.text}" }
        val senderName = Views.battleFrame?.opponentUserInfo?.nickname ?: "Player"
        swing {
            Views.battleFrame?.appendChat("$senderName: ${push.text}\n")
        }
    }

    /** 加入聊天室响应（原 ChatRoomActor.joinChatRoom） */
    suspend fun onJoinChatRoomResponse(msg: NetMessage) {
        logger.info { "【加入聊天室响应】errorCode=${msg.errorCode}" }
    }

    /** 强制下线推送（原 ForceOfflinePushActor） */
    suspend fun onForceOfflinePush(msg: NetMessage) {
        logger.error { "【强制下线推送】...." }
        swingDialog("Force Off line !!")
        kotlin.system.exitProcess(1)
    }

    private fun swing(block: () -> Unit) {
        javax.swing.SwingUtilities.invokeLater(block)
    }

    private fun swingDialog(text: String) {
        swing { JOptionPane.showMessageDialog(null, text) }
    }
}
