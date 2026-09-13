package com.jacey.game.common.akka

import akka.actor.ActorRef
import akka.actor.Terminated
import com.jacey.game.common.exception.RpcErrorException
import com.jacey.game.common.framework.akka.CoroutineActor
import com.jacey.game.common.msg.IMessage
import com.jacey.game.common.msg.LocalMessage
import com.jacey.game.common.msg.NetMessage
import com.jacey.game.common.msg.RemoteMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 基础消息处理 Actor（协程化）
 *
 * 替代原 Java 版 BaseMessageActor + ClassScanner + reflectasm 反射分发：
 * - 消息处理器在构造时由子类显式注册（registerHandler），无注解扫描、无反射调用
 * - 处理器为 suspend 函数：内部可挂起等待 DB/远端 actor，写法同步、执行非阻塞
 * - 本 actor 内部单协程串行消费（同 CoroutineActor），保证状态安全
 */
abstract class BaseMessageActor : CoroutineActor() {

    private val log = KotlinLogging.logger(this::class.java.name)

    /** 消息处理器注册表：msgClass -> handler(msg, sender) */
    private val handlers = HashMap<Class<*>, suspend (Any, ActorRef?) -> Unit>()

    /** 子类构造时注册消息处理器（sender 为回信目标，可为 null） */
    fun <T : Any> registerHandler(clz: Class<T>, handler: suspend (T, ActorRef?) -> Unit) {
        require(!handlers.containsKey(clz)) { "duplicate handler for ${clz.name} in ${this::class.simpleName}" }
        @Suppress("UNCHECKED_CAST")
        handlers[clz] = handler as suspend (Any, ActorRef?) -> Unit
    }

    /*
    重点!!! 进行消息处理
     */
    final override suspend fun onMessage(msg: Any, sender: ActorRef?) {
        when (msg) {
            is Terminated -> onTerminated(msg)
            is NetMessage -> handleMessage(msg, sender)
            is RemoteMessage -> handleMessage(msg, sender)
            is LocalMessage -> handleMessage(msg, sender)
            else -> log.error { "unsupported msg type: ${msg::class.qualifiedName}" }
        }
    }

    private suspend fun handleMessage(msg: IMessage, sender: ActorRef?) {
        // 从工厂找出注册的消息进行处理
        val handler = handlers[msg::class.java]
        if (handler != null) {
            try {
                handler(msg, sender)
            } catch (e: RpcErrorException) {
                when (msg) {
                    // 因为现在是采用抛出异常方式，因此这里进行错误处理
                    is NetMessage -> sendErrorToClient(msg, e.errorCode, sender)
                    is RemoteMessage -> sendErrorToRemoteServer(msg, e.errorCode, sender)
                    else -> log.error(e) { "RpcErrorException on local msg rpcNum=${msg.msgId}" }
                }
            } catch (e: Exception) {
                log.error(e) { "handle msg fail, rpcNum=${msg.msgId}" }
            }
        } else {
            log.error { "no handler for ${msg::class.simpleName} rpcNum=${msg.msgId}" }
        }
    }

    /** Terminated 处理钩子（子类覆写） */
    protected open suspend fun onTerminated(terminated: Terminated) {

    }

    protected fun sendErrorToClient(netMessage: NetMessage, errorCode: Int, sender: ActorRef?) {
        val resp = NetMessage(netMessage.msgId, errorCode)
        sender?.tell(resp, ActorRef.noSender())
    }

    protected fun sendErrorToRemoteServer(remoteMessage: RemoteMessage, errorCode: Int, sender: ActorRef?) {
        val resp = RemoteMessage(remoteMessage.msgId, errorCode)
        sender?.tell(resp, self())
    }

    companion object {
        /** 构造带类型键的注册辅助（由 Kotlin reified 使用） */
        inline fun <reified T> typeOf(): Class<T> {
            return T::class.java
        }
    }
}

/** 定时任务调度辅助：以毫秒间隔向指定 actor 发送消息（非阻塞，基于协程 delay） */
fun scheduleMsg(
    scope: CoroutineScope,
    actor: ActorRef,
    msg: IMessage,
    initialDelayMs: Long,
    intervalMs: Long,
): kotlinx.coroutines.Job {
    return scope.launch(CoroutineName("schedule-${msg.msgId}")) {
        kotlinx.coroutines.delay(initialDelayMs)
        while (isActive) {
            actor.tell(msg, ActorRef.noSender())
            kotlinx.coroutines.delay(intervalMs)
        }
    }
}

