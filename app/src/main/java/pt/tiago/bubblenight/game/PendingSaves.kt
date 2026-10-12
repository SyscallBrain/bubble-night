package pt.tiago.bubblenight.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps failed writes in order until they succeed. Enqueue/read flags on the UI dispatcher. */
internal class PendingSaves<T> {
    private val writes = ArrayDeque<suspend () -> T>()
    private val mutex = Mutex()
    var failed: Boolean = false
        private set
    val pending: Boolean get() = writes.isNotEmpty()

    fun enqueue(write: suspend () -> T) { writes.addLast(write) }

    suspend fun flush(): T? = mutex.withLock {
        var latest: T? = null
        while (writes.isNotEmpty()) {
            try {
                latest = writes.first().invoke()
                writes.removeFirst()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failed = true; throw error }
        }
        failed = false
        latest
    }
}
