package com.jacey.game.common.framework.process

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 全局协程调度器工厂
 *
 * 两类共享线程池：
 * - Scheduler: 定时任务调度线程池（小规模）
 * - Actor:     Actor 消息处理线程池（大规模，所有 Actor 协程共用）
 *
 * 线程均 daemon 模式，进程退出自动终止。
 */
object Dispatcher {
    private val logger = KotlinLogging.logger {}
    private val cpuCore = Runtime.getRuntime().availableProcessors()
    private val newThreadId = AtomicInteger(0)

    private fun createDispatcher(name: String, minSize: Int, maxSize: Int): CoroutineDispatcher {
        val corePoolSize = minSize.coerceAtLeast(1)
        val maximumPoolSize = maxSize.coerceIn(corePoolSize, 128)
        logger.info { "create dispatcher $name, corePoolSize=$corePoolSize, maximumPoolSize=$maximumPoolSize" }
        return ThreadPoolExecutor(
            corePoolSize,
            maximumPoolSize,
            30, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { runnable ->
            Thread(runnable, "$name-${newThreadId.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** 定时任务调度器（2 ~ CPU/2 个线程） */
    val Scheduler: CoroutineDispatcher = createDispatcher("Scheduler", 2, cpuCore / 2)

    /** Actor 消息处理线程池（至少 10 个线程，最多 CPU*2） */
    val Actor: CoroutineDispatcher = createDispatcher("Actor", cpuCore.coerceAtLeast(10), cpuCore * 2)

    /** Ktor/IO 混合使用的弹性调度器（虚拟线程池化由协程管理） */
    val Io: CoroutineDispatcher = Dispatchers.Default
}
