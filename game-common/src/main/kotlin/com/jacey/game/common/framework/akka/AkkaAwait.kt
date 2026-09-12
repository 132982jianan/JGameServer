package com.jacey.game.common.framework.akka

import akka.actor.ActorNotFound
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.pattern.Patterns
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 协程 <-> Akka 桥接（本次重构重点）
 *
 * 目标：actor 间通信"同步写法、异步效果"——
 * 业务代码按顺序 await 回复，写法与阻塞调用一致；
 * 底层通过 suspendCancellableCoroutine 桥接 akka 的 CompletionStage，
 * 等待期间线程被挂起释放，不阻塞 Dispatcher 线程。
 */

/** 挂起式 ask：向目标 actor 发消息并挂起等待回复（非阻塞）。业务侧配合 as 转型使用 */
suspend fun ActorRef.askAwait(
    msg: Any,
    timeout: Duration = 5.seconds,
): Any? = suspendCancellableCoroutine { cont ->
    val future: java.util.concurrent.CompletionStage<Any> =
        Patterns.ask(this, msg, java.time.Duration.ofMillis(timeout.inWholeMilliseconds))
    future.whenComplete { result, error ->
        when {
            error != null -> cont.cancel(error)
            else -> cont.resume(result)
        }
    }
    // 协程被取消时（调用方超时/actor 关闭），取消底层 ask 防止泄漏
    cont.invokeOnCancellation { future.toCompletableFuture().cancel(true) }
}

/** askAwait 的带类型版本：调用点写 askAwaitAs<Foo>(msg) */
@Suppress("UNCHECKED_CAST")
suspend fun <T> ActorRef.askAwaitAs(
    msg: Any,
    timeout: Duration = 5.seconds,
): T = askAwait(msg, timeout) as T

/** 挂起式 actorSelection 解析：把 path 解析为 ActorRef（桥接 resolveOneCS，非阻塞） */
suspend fun ActorSelection.resolveAwait(timeout: Duration = 5.seconds): ActorRef =
    suspendCancellableCoroutine { cont ->
        val future = resolveOneCS(java.time.Duration.ofMillis(timeout.inWholeMilliseconds))
        future.whenComplete { ref, error ->
            when {
                error != null -> cont.cancel(error)
                else -> cont.resume(ref)
            }
        }
        cont.invokeOnCancellation { future.toCompletableFuture().cancel(true) }
    }
