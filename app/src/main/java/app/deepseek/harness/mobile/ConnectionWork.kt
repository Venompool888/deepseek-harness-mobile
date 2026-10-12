package app.deepseek.harness.mobile

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** A lease includes profile generation even when two profiles share an origin. */
internal data class ConnectionLease<T>(val generation: Long, val value: T)
internal class ConnectionWorkOwner<T>(initial: T) {
    private val lock = Any()
    private var selected = ConnectionLease(0L, initial)
    private val scope = ThreadLocal<ConnectionLease<T>>()
    fun replace(value: T) = synchronized(lock) { selected = ConnectionLease(selected.generation + 1, value) }
    fun capture(): ConnectionLease<T> = scope.get() ?: synchronized(lock) { selected }
    fun value(): T = capture().value
    fun isCurrent(lease: ConnectionLease<T>): Boolean = synchronized(lock) { lease.generation == selected.generation }
    fun bind(task: Runnable): Runnable = bind(capture(), task)
    fun bind(lease: ConnectionLease<T>, task: Runnable, discarded: Runnable = Runnable {}): Runnable = Runnable {
        if (!isCurrent(lease)) { discarded.run(); return@Runnable }
        val previous = scope.get()
        scope.set(lease)
        try { task.run() } finally { if (previous == null) scope.remove() else scope.set(previous) }
    }
    fun callback(task: Runnable): Runnable = callback(capture(), task)
    fun callback(lease: ConnectionLease<T>, task: Runnable): Runnable = Runnable { if (isCurrent(lease)) task.run() }
}
internal class ConnectionWorkExecutor<T>(private val delegate: ExecutorService, private val owner: ConnectionWorkOwner<T>) : AbstractExecutorService() {
    override fun execute(command: Runnable) = delegate.execute(owner.bind(command))
    fun execute(lease: ConnectionLease<T>, discarded: Runnable, command: Runnable) = delegate.execute(owner.bind(lease, command, discarded))
    override fun shutdown() = delegate.shutdown()
    override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
    override fun isShutdown() = delegate.isShutdown
    override fun isTerminated() = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
}
