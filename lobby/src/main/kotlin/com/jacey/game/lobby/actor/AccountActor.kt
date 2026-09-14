package com.jacey.game.lobby.actor

import akka.actor.ActorRef
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.db.Db
import com.jacey.game.common.framework.mongo.MongoSequence
import com.jacey.game.common.framework.akka.askAwait
import com.jacey.game.common.framework.net.NacosService
import com.jacey.game.common.framework.net.NodeKind
import com.jacey.game.common.msg.InternalMessageId
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.proto3.CommonEnum
import com.jacey.game.common.proto3.CommonMsg
import com.jacey.game.common.proto3.Rpc
import com.jacey.game.db.AccountId
import com.jacey.game.db.LoginType
import com.jacey.game.db.table.DbAccount
import java.util.Date

data class AccountActorState(
    val account: DbAccount,
    val gateActor: ActorRef,
    var playerActor: ActorRef? = null,
    var playerId: Int = 0,
)

/** 账号维度 Actor：占有锁、顶号、创建角色以及 Gate/Player 生命周期编排。 */
class AccountActor(
    private val accountId: String,
    private val lobbyRootActor: ActorRef,
) : BaseMessageActor() {
    private var state: AccountActorState? = null

    init {
        registerHandler(NetMessage::class.java) { msg, sender -> onNetMessage(msg, sender) }
        registerHandler(LocalMessage::class.java) { msg, _ -> onLocalMessage(msg) }
    }

    private suspend fun onNetMessage(msg: NetMessage, sender: ActorRef?) {
        when (msg.msgId) {
            Rpc.RpcNameEnum.Login_VALUE -> login(msg, sender)
            InternalMessageId.ACCOUNT_LOGOUT -> logout(notifyGate = false)
        }
    }

    private suspend fun onLocalMessage(msg: LocalMessage) {
        when (msg.msgId) {
            InternalMessageId.PLAYER_LOGIN_RESULT -> {
                val result = msg.lite as? PlayerLoginResult ?: return
                if (result.success) {
                    state?.playerActor = result.playerActor
                    state?.playerId = result.playerId
                } else {
                    state?.gateActor?.tell(
                        NetMessage(Rpc.RpcNameEnum.Login_VALUE, Rpc.RpcErrorCodeEnum.ServerError_VALUE),
                        self(),
                    )
                    logout(notifyGate = false)
                }
            }
        }
    }

    private suspend fun login(msg: NetMessage, gateActor: ActorRef?) {
        if (gateActor == null) return

        // 与 reference code 一致：旧端被踢，本次登录也失败，客户端再次点击才会成功。
        if (state != null) {
            forceOffline(state!!.gateActor)
            gateActor.tell(
                NetMessage(Rpc.RpcNameEnum.Login_VALUE, Rpc.RpcErrorCodeEnum.LoginErrorAlreadyLogin_VALUE),
                self(),
            )
            logout(notifyGate = false)
            return
        }

        val request = msg.getProto<CommonMsg.LoginRequest>() ?: return reject(gateActor)
        val loginName = request.loginName.trim()
        val expected = AccountId.createAccountIdByLoginTypeAndLoginName(LoginType.LoginDebug, loginName).toString()
        if (expected != accountId) return reject(gateActor)

        val now = Date()
        var account = Db.dbAccount.findOne(accountId)
        if (account == null) {
            val playerId = MongoSequence.nextId("Player")
            val created = DbAccount(
                _id = accountId,
                loginName = loginName,
                lobbyId = NacosService.selfNodeId,
                loginTimestamp = now,
                playerList = mutableListOf(DbAccount.Player(playerId, now, now)),
            )
            if (!Db.dbAccount.insert(created)) {
                kickForeignOccupierAndReject(gateActor)
                return
            }
            account = created
        } else {
            val oldLobbyId = account.lobbyId
            if (oldLobbyId != null && oldLobbyId != NacosService.selfNodeId) {
                kickForeignOccupierAndReject(gateActor, account)
                return
            }

            val oldLoginTimestamp = account.loginTimestamp
            account.lobbyId = NacosService.selfNodeId
            account.loginTimestamp = now
            account.logoutTimestamp = null
            if (account.playerList.isEmpty()) {
                account.playerList.add(DbAccount.Player(MongoSequence.nextId("Player"), now, now))
            } else {
                account.playerList.first().loginTimestamp = now
            }
            if (!Db.dbAccount.replaceOne(
                    DbAccount::lobbyId, oldLobbyId,
                    DbAccount::loginTimestamp, oldLoginTimestamp,
                    account,
                )
            ) {
                kickForeignOccupierAndReject(gateActor)
                return
            }
        }

        val playerId = account.playerList.first().playerId
        state = AccountActorState(account, gateActor, playerId = playerId)
        val playerActorReply = lobbyRootActor.askAwait(
            LocalMessage(
                InternalMessageId.LOBBY_GET_OR_CREATE_PLAYER,
                LobbyPlayerActorKey(playerId, accountId),
            ),
        ) as? LocalMessage
        val playerActor = playerActorReply?.lite as? ActorRef ?: run {
            reject(gateActor)
            return
        }
        state?.playerActor = playerActor
        playerActor.tell(
            LocalMessage(
                InternalMessageId.PLAYER_LOGIN,
                PlayerLogin(msg, loginName, gateActor, self()),
            ),
            self(),
        )
    }

    private suspend fun kickForeignOccupierAndReject(gateActor: ActorRef, loaded: DbAccount? = null) {
        val account = loaded ?: Db.dbAccount.findOne(accountId)
        val oldLobbyId = account?.lobbyId
        if (oldLobbyId != null && oldLobbyId != NacosService.selfNodeId) {
            val kick = NetMessage(InternalMessageId.ACCOUNT_LOGOUT, accountId.toByteArray())
            NacosService.getActorRefByNodeKindAndNodeId(NodeKind.lobby, oldLobbyId)
                ?.tell(kick, self())
        }

        // 不可达节点留下的占有超过一分钟时，清脏但仍拒绝本次登录，行为与 reference code 相同。
        val loginAt = account?.loginTimestamp
        if (account != null && oldLobbyId != null && loginAt != null && Date().time - loginAt.time > 60_000) {
            Db.dbAccount.findOneAndUpdate(
                accountId,
                DbAccount::lobbyId, oldLobbyId, null,
                DbAccount::loginTimestamp, loginAt, null,
            )
        }
        reject(gateActor)
    }

    private fun reject(gateActor: ActorRef) {
        gateActor.tell(
            NetMessage(Rpc.RpcNameEnum.Login_VALUE, Rpc.RpcErrorCodeEnum.LoginErrorAlreadyLogin_VALUE),
            self(),
        )
        context().stop(self())
    }

    private suspend fun logout(notifyGate: Boolean) {
        val current = state ?: run {
            context().stop(self())
            return
        }
        current.playerActor?.tell(LocalMessage(InternalMessageId.PLAYER_LOGOUT), self())
        if (notifyGate) forceOffline(current.gateActor)

        val account = current.account
        val oldLobbyId = account.lobbyId
        val oldLoginTimestamp = account.loginTimestamp
        account.lobbyId = null
        account.loginTimestamp = null
        account.logoutTimestamp = Date()
        Db.dbAccount.replaceOne(
            DbAccount::lobbyId, oldLobbyId,
            DbAccount::loginTimestamp, oldLoginTimestamp,
            account,
        )
        state = null
        context().stop(self())
    }

    private fun forceOffline(gateActor: ActorRef) {
        val push = CommonMsg.ForceOfflinePush.newBuilder()
            .setForceOfflineReason(CommonEnum.ForceOfflineReasonEnum.ForceOfflineSameUserLogin)
        gateActor.tell(NetMessage(Rpc.RpcNameEnum.RpcForceOfflinePush_VALUE, push), self())
    }
}
