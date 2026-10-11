package dev.apkproject.desktop

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** One background reader with at most one waiting request. Only the latest result reaches Swing. */
internal class LatestTask(name: String) : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) {
        Thread(it, name).apply { isDaemon = true }
    }
    private var generation = 0L
    private var future: Future<*>? = null
    private var closed = false
    var isBusy = false
        private set

    fun <T> submit(load: () -> T, success: (T) -> Unit, failure: (Exception) -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        cancel()
        val request = generation
        isBusy = true
        future = executor.submit {
            val result = try { Result.success(load()) } catch (e: Exception) { Result.failure(e) }
            SwingUtilities.invokeLater {
                if (!closed && request == generation) {
                    isBusy = false
                    result.fold(success) { failure(it as Exception) }
                }
            }
        }
    }

    fun cancel() {
        check(SwingUtilities.isEventDispatchThread())
        generation++
        future?.cancel(true)
        future = null
        executor.queue.clear()
        isBusy = false
    }

    override fun close() {
        cancel()
        closed = true
        executor.shutdownNow()
    }
}
