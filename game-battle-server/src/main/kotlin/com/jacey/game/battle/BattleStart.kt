package com.jacey.game.battle

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import com.jacey.game.common.proto3.LocalServer
import com.jacey.game.common.proto3.RemoteServer
import com.jacey.game.common.framework.akka.Akka
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.framework.net.NodeRegister

/**
 * 对战服启动器：组装 actors + Nacos 注册
 */
object BattleStart {
    suspend fun startBusiness(): Boolean {
        NodeRegister.subscribe(NodeKind.gm)
        NodeRegister.subscribe(NodeKind.chat)
        AkkaRefsB.battleServerActor = Akka.create<BattleServerActor>("battleServerActor")
        Akka.create<BattleRoomManagerProxy>("battleRoomManagerActor")
        Akka.create<BattleActionActor>("battleActionActor")
        return true
    }
}

object AkkaRefsB {
    var battleServerActor: ActorRef? = null
}

/**
 * 房间管理代理 actor：把房间创建请求转发给 BattleRooms 单例处理
 * （原 BattleRoomManagerActor 的创建战场入口）
 */
class BattleRoomManagerProxy : BaseMessageActor() {
    init {
        registerHandler(RemoteMessage::class.java) { msg, sender ->
            when (msg.rpcNum) {
                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeBattleServerCreateNewBattle_VALUE -> {
                    val request = msg.getProto<RemoteServer.NoticeBattleServerCreateNewBattleRequest>()
                    if (request != null) {
                        BattleRooms.createNewBattle(request, sender())
                    }
                }
                RemoteServer.RemoteRpcNameEnum.RemoteRpcNoticeChatServerCreateNewBattleChatRoom_VALUE -> {
                    // 聊天室创建响应（TODO 原版同）
                }
            }
        }
    }
}
