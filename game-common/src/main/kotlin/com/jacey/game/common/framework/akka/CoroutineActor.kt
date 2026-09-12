package com.jacey.game.common.framework.akka

import akka.actor.UntypedAbstractActor
import akka.actor.ActorRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 协程化 Akka Actor 基类（本次重构重点）
 *
 * 结构：
 * - 外壳是普通 akka classic actor：保留 akka remote/artery、mailbox、序列化等全部能力，
 *   远端消息照常进入本 actor 的 mailbox，由 receive 逻辑转投内部 Channel（非阻塞 trySend）。
 * - 内核是一个并发度=1 的协程，顺序消费 Channel 并调用挂起函数 onMessage()。
 *
 * 语义：
 * - 串行：onMessage() 挂起（等 Mongo / 远端 actor 回包）期间，新消息在 Channel 排队等待，
 *   不并发进入，同一 actor 内的"读改写"逻辑天然线程安全。
 * - 非阻塞：挂起期间不占用 Dispatcher 线程，线程可调度其它 actor 的协程。
 *
 * 子类只实现 onMessage()，写法与同步代码一致。
 */
abstract class CoroutineActor(
    /** Channel 容量；SUSPEND 溢出策略下容量为背压上限 */
    private val capacity: Int = Channel.UNLIMITED,
    /** 溢出策略：默认挂起投递者（背压），可选 DROP_LATEST */
    private val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
) : UntypedAbstractActor() {

    val logger = KotlinLogging.logger { }

    /** 消息信封：附带发送者引用，供 onMessage 内 reply 使用 */
    data class Envelope(val msg: Any, val sender: ActorRef?)

    private val channel = Channel<Envelope>(capacity, onBufferOverflow)
    private var loopJob: Job? = null

    /** actor 启动：拉起消费协程（并发度=1，串行消费 Channel） */
    override fun preStart() {
        val scope = CoroutineScope(
            com.jacey.game.common.framework.process.Dispatcher.Actor + CoroutineName(
                self().path().name()
            )
        )
        loopJob = scope.launch {
            for (envelope in channel) {
                try {
                    onMessage(envelope.msg, envelope.sender)
                } catch (e: Exception) {
                    logger.error(e) { "onMessage fail: ${envelope.msg}" }
                }
            }
        }
    }

    /** actor 停止：关闭 Channel，等消费协程清算完剩余消息再退出 */
    override fun postStop() {
        channel.close()
        // postStop 不能挂起；runBlocking 仅发生在进程关闭路径
        loopJob?.let { runBlocking { it.join() } }
    }

    /** akka 外壳 onReceive：转投 Channel，绝不阻塞 mailbox 线程 */
    final override fun onReceive(message: Any) {
        val envelope = Envelope(message, sender())
        if (!channel.trySend(envelope).isSuccess) {
            logger.warn { "channel full/closed, drop msg: $message" }
        }
    }


    /** 子类实现：挂起式消息处理。执行期间本 actor 的后续消息排队等待 */
    abstract suspend fun onMessage(msg: Any, sender: ActorRef?)
}
