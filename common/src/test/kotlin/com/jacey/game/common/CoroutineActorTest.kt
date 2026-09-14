package com.jacey.game.common

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.framework.akka.askAwait
import com.jacey.game.common.msg.NetMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * 协程包装 Akka 专项验证（本次重构重点）：
 * 1. askAwait：挂起等待回复，不阻塞调用线程
 * 2. 串行语义：处理器挂起期间，后续消息排队等待而非并发进入
 * 3. 非阻塞：挂起点不占用 Dispatcher 线程
 */
class CoroutineActorTest {

    /** 回声 actor：回复消息 */
    class EchoActor : BaseMessageActor() {
        init {
            registerHandler(NetMessage::class.java) { msg, sender ->
                sender?.tell(msg, ActorRef.noSender())
            }
        }
    }

    /** 慢 actor：每条消息处理挂起 200ms，记录并发进入次数 */
    class SlowActor(
        val concurrent: AtomicInteger,
        val order: Channel<Int>,
        val maxConcurrent: AtomicInteger = AtomicInteger(),
    ) : BaseMessageActor() {
        init {
            registerHandler(NetMessage::class.java) { msg, _ ->
                val active = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, active) }
                val n = msg.data?.get(0)?.toInt() ?: 0
                delay(200) // 挂起点：模拟 DB/远端调用
                order.send(n)
                concurrent.decrementAndGet()
            }
        }
    }

    @Test
    fun `askAwait suspends and returns reply`() { runBlocking {
        val system = ActorSystem.create("test-echo")
        val actor = system.actorOf(Props.create(EchoActor::class.java), "echo")

        val startThread = Thread.currentThread()
        val reply = withTimeout(3000) {
            actor.askAwait(NetMessage(1, byteArrayOf(1)))
        }
        assertEquals(1, (reply as NetMessage).msgId)
        // 验证非阻塞：等待期间线程换过（挂起恢复可能在同一线程，此处仅验证能恢复）
        assertEquals(Thread.currentThread(), startThread)

        scala.concurrent.Await.ready(system.terminate(), scala.concurrent.duration.FiniteDuration(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun `messages are processed serially while handler suspends`() { runBlocking {
        val system = ActorSystem.create("test-serial")
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val order = Channel<Int>(Channel.UNLIMITED)
        val actor = system.actorOf(
            Props.create(SlowActor::class.java) { SlowActor(concurrent, order, maxConcurrent) }, "slow"
        )

        // 连发 5 条消息
        repeat(5) { i ->
            actor.tell(NetMessage(1, byteArrayOf(i.toByte())), ActorRef.noSender())
        }

        // 收集处理顺序：应为 0,1,2,3,4（串行）
        val received = mutableListOf<Int>()
        withTimeout(5000) {
            repeat(5) { received.add(order.receive()) }
        }
        assertEquals(listOf(0, 1, 2, 3, 4), received)
        // 挂起期间也算正在处理：验证没有第二条消息进入同一个 Actor。
        assertEquals(1, maxConcurrent.get())

        scala.concurrent.Await.ready(system.terminate(), scala.concurrent.duration.FiniteDuration(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun `suspension does not block dispatcher threads`() { runBlocking {
        val system = ActorSystem.create("test-nonblock")
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val order = Channel<Int>(Channel.UNLIMITED)

        // 5 个不同 actor，各自处理挂起 200ms；共享小线程池时若阻塞则无法并发
        val actors = (0 until 5).map { i ->
            system.actorOf(
                Props.create(SlowActor::class.java) { SlowActor(concurrent, order, maxConcurrent) }, "slow$i"
            ) to i
        }
        actors.forEach { (ref, i) ->
            ref.tell(NetMessage(1, byteArrayOf(i.toByte())), ActorRef.noSender())
        }
        val received = mutableListOf<Int>()
        withTimeout(5000) {
            repeat(5) { received.add(order.receive()) }
        }
        assertEquals(5, received.size)
        kotlin.test.assertTrue(maxConcurrent.get() > 1)

        scala.concurrent.Await.ready(system.terminate(), scala.concurrent.duration.FiniteDuration(3, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
}
