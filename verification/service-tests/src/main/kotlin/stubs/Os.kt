package android.os
import java.util.PriorityQueue

interface IBinder

interface OutcomeReceiver<R, E : Throwable> {
    fun onResult(result: R)

    fun onError(error: E)
}

class Looper {
    companion object {
        private val main = Looper()

        fun getMainLooper() = main
    }
}

object SystemClock {
    fun elapsedRealtime() = TestQueue.now

    fun uptimeMillis() = TestQueue.uptime
}

object TestQueue {
    data class Task(
        val at: Long,
        val id: Long,
        val owner: Handler,
        val action: Runnable,
    )

    private val q = PriorityQueue<Task>(compareBy<Task> { it.at }.thenBy { it.id })
    var now = 0L
    var uptime = 0L
    private var serial = 0L

    fun reset() {
        q.clear()
        now = 0L
        uptime = 0L
        serial = 0L
    }

    fun add(
        owner: Handler,
        r: Runnable,
        delay: Long,
    ) {
        q.add(Task(uptime + delay, ++serial, owner, r))
    }

    fun remove(
        owner: Handler,
        r: Runnable?,
    ) {
        q.removeIf { it.owner === owner && (r == null || it.action === r) }
    }

    fun runReady() {
        var n = 0
        while (q.isNotEmpty() && q.peek().at <= uptime) {
            check(n++ < 1000) { "Runaway handler" }
            q.remove().action.run()
        }
    }

    fun advanceTo(value: Long) {
        require(value >= now)
        uptime += value - now
        now = value
        runReady()
    }

    /** Elapsed time advances during device sleep while Handler's uptime-based queue does not. */
    fun sleepFor(duration: Long) {
        require(duration >= 0)
        now += duration
    }

    fun size() = q.size
}

class Handler(
    val looper: Looper,
) {
    fun post(r: Runnable): Boolean {
        TestQueue.add(this, r, 0)
        return true
    }

    fun postDelayed(
        r: Runnable,
        delay: Long,
    ): Boolean {
        TestQueue.add(this, r, delay)
        return true
    }

    fun removeCallbacks(r: Runnable) {
        TestQueue.remove(this, r)
    }

    fun removeCallbacksAndMessages(token: Any?) {
        TestQueue.remove(this, null)
    }
}
