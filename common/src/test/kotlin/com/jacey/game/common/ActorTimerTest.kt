package com.jacey.game.common

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Kill
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.actor.UntypedAbstractActor
import akka.japi.pf.DeciderBuilder
import com.jacey.game.common.akka.BaseMessageActor
import com.jacey.game.common.msg.LocalMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ActorTimerTest {
    class Command(val action: suspend TimerActor.() -> Unit)

    class TimerActor(
        private val starts: Channel<ActorRef>? = null,
        private val stopped: CompletableDeferred<Unit>? = null,
    ) : BaseMessageActor() {
        var value = 0

        init {
            registerHandler(LocalMessage::class.java) { msg, _ ->
                (msg.lite as Command).action(this)
            }
        }

        override fun preStart() {
            super.preStart()
            starts?.trySend(self())
        }

        override fun postStop() {
            super.postStop()
            stopped?.complete(Unit)
        }
    }

    class RestartSupervisor(private val starts: Channel<ActorRef>) : UntypedAbstractActor() {
        override fun supervisorStrategy(): SupervisorStrategy = OneForOneStrategy(
            DeciderBuilder.matchAny { SupervisorStrategy.restart() }.build(),
        )

        override fun preStart() {
            context().actorOf(Props.create(TimerActor::class.java) { TimerActor(starts) }, "timer")
        }

        override fun onReceive(message: Any) = unhandled(message)
    }

    private fun ActorRef.command(action: suspend TimerActor.() -> Unit) {
        tell(LocalMessage(1, Command(action)), ActorRef.noSender())
    }

    private fun withActorSystem(test: suspend (ActorSystem) -> Unit) = runBlocking {
        val system = ActorSystem.create("timer-test-${UUID.randomUUID()}")
        try {
            withTimeout(10.seconds) { test(system) }
        } finally {
            Await.ready(system.terminate(), FiniteDuration(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `timer and ordinary messages stay serial across suspension`() = withActorSystem { system ->
        val actor = system.actorOf(Props.create(TimerActor::class.java) { TimerActor() })
        val messageStarted = CompletableDeferred<Unit>()
        val releaseMessage = CompletableDeferred<Unit>()
        val timerStarted = CompletableDeferred<Int>()
        val releaseTimer = CompletableDeferred<Unit>()
        val nextMessage = CompletableDeferred<Int>()
        val timerJob = CompletableDeferred<Job>()
        try {
            actor.command {
                value = 1
                timerJob.complete(timer(Duration.ZERO) {
                    timerStarted.complete(value)
                    releaseTimer.await()
                    value = 3
                })
                messageStarted.complete(Unit)
                releaseMessage.await()
                value = 2
            }
            messageStarted.await()
            // Job 完成只代表请求已投递；当前消息挂起时，定时处理器仍不能进入。
            timerJob.await().join()
            assertNull(withTimeoutOrNull(100.milliseconds) { timerStarted.await() })
            releaseMessage.complete(Unit)
            assertEquals(2, timerStarted.await())

            actor.command { nextMessage.complete(value) }
            assertNull(withTimeoutOrNull(100.milliseconds) { nextMessage.await() })
            releaseTimer.complete(Unit)
            assertEquals(3, nextMessage.await())
            timerJob.await().join()
            assertTrue(timerJob.await().isCompleted)
        } finally {
            releaseMessage.complete(Unit)
            releaseTimer.complete(Unit)
        }
    }

    @Test
    fun `timer waits for its duration and fires once`() = withActorSystem { system ->
        val actor = system.actorOf(Props.create(TimerActor::class.java) { TimerActor() })
        val elapsed = CompletableDeferred<Long>()
        val handle = CompletableDeferred<Job>()
        val valueAfter = CompletableDeferred<Int>()
        actor.command {
            val start = System.nanoTime()
            handle.complete(timer(50.milliseconds) {
                value++
                elapsed.complete(System.nanoTime() - start)
            })
        }
        assertTrue(elapsed.await() >= 50.milliseconds.inWholeNanoseconds)
        handle.await().join()
        actor.command { valueAfter.complete(value) }
        assertEquals(1, valueAfter.await())
    }

    @Test
    fun `cancel suppresses a timer before delivery`() = withActorSystem { system ->
        val actor = system.actorOf(Props.create(TimerActor::class.java) { TimerActor() })
        val result = CompletableDeferred<Int>()
        val handle = CompletableDeferred<Job>()
        actor.command {
            val delayed = timer(1.hours) { value++ }
            delayed.cancel()
            handle.complete(delayed)
            timer(Duration.ZERO) { result.complete(value) }
        }
        assertEquals(0, result.await())
        handle.await().join()
        assertTrue(handle.await().isCancelled)
    }

    @Test
    fun `stopping an actor cancels its pending timers`() = withActorSystem { system ->
        val actor = system.actorOf(Props.create(TimerActor::class.java) { TimerActor() })
        val fired = AtomicInteger()
        val handle = CompletableDeferred<Job>()
        actor.command { handle.complete(timer(1.hours) { fired.incrementAndGet() }) }
        val job = handle.await()
        system.stop(actor)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, fired.get())
    }

    @Test
    fun `stopping skips a timer already waiting behind a suspended message`() = withActorSystem { system ->
        val stopped = CompletableDeferred<Unit>()
        val actor = system.actorOf(Props.create(TimerActor::class.java) { TimerActor(stopped = stopped) })
        val fired = AtomicInteger()
        val handles = CompletableDeferred<Pair<Job, Job>>()
        val release = CompletableDeferred<Unit>()
        try {
            actor.command {
                val queued = timer(Duration.ZERO) { fired.incrementAndGet() }
                val pending = timer(1.hours) { fired.incrementAndGet() }
                handles.complete(queued to pending)
                release.await()
            }
            val (queued, pending) = handles.await()
            queued.join()
            system.stop(actor)
            // 等待生命周期取消开始，再允许当前消息结束并清算队列。
            pending.join()
            release.complete(Unit)
            stopped.await()
            assertEquals(0, fired.get())
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `restart cancels old timers and new instance can schedule`() = withActorSystem { system ->
        val starts = Channel<ActorRef>(Channel.UNLIMITED)
        system.actorOf(Props.create(RestartSupervisor::class.java) { RestartSupervisor(starts) })
        val actor = starts.receive()
        val oldJob = CompletableDeferred<Job>()
        val fired = AtomicInteger()
        actor.command { oldJob.complete(timer(1.hours) { fired.incrementAndGet() }) }
        val pending = oldJob.await()

        actor.tell(Kill.getInstance(), ActorRef.noSender())
        assertEquals(actor, starts.receive())
        pending.join()
        assertTrue(pending.isCancelled)

        val newResult = CompletableDeferred<Int>()
        actor.command { timer(Duration.ZERO) { newResult.complete(++value) } }
        assertEquals(1, newResult.await())
        assertEquals(0, fired.get())
    }

    @Test
    fun `callback can rearm in finally after failure`() = withActorSystem { system ->
        val actor = system.actorOf(Props.create(TimerActor::class.java) { TimerActor() })
        val nextTick = CompletableDeferred<Unit>()
        actor.command {
            timer(Duration.ZERO) {
                try {
                    error("simulated matching failure")
                } finally {
                    timer(1.milliseconds) { nextTick.complete(Unit) }
                }
            }
        }
        nextTick.await()
        val nextMessage = CompletableDeferred<Unit>()
        actor.command { nextMessage.complete(Unit) }
        nextMessage.await()
    }
}
