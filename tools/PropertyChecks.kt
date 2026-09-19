import org.carcallrouter.companion.core.RoutingPolicy
import org.carcallrouter.companion.core.RoutingPolicy.Route
import org.carcallrouter.companion.core.RoutingPolicy.Phase
import org.carcallrouter.companion.core.RoutingPolicy.Snapshot
import kotlin.random.Random
fun main() {
    val random = Random(20260917)
    val terminal = setOf(Phase.RELEASED, Phase.SUSPENDED, Phase.FAILED)
    var transitions = 0
    var requests = 0
    repeat(5000) {
        val manual = random.nextInt(5) == 0
        val initial = if (random.nextBoolean()) Route.COMPETING_DEVICE else Route.values().random(random)
        val p = RoutingPolicy()
        p.begin(0, initial, manual)
        var now = 0L
        var lastRequest: Long? = null
        repeat(50) {
            now += random.nextInt(0, 181)
            val route = when (random.nextInt(10)) {
                in 0..4 -> Route.COMPETING_DEVICE
                in 5..7 -> Route.TARGET
                else -> Route.values().random(random)
            }
            if (random.nextInt(100) == 0) p.suspend("explicit pause")
            if (random.nextInt(4) == 0) p.observeRoute(route, now)
            fun evidence(): Boolean? = when (random.nextInt(100)) { 0 -> false; in 1..4 -> null; else -> true }
            val s = Snapshot(now, enabled = random.nextInt(100) > 1,
                authorized = random.nextInt(100) > 1, active = random.nextInt(100) > 1,
                singleCall = random.nextInt(100) > 1, safeCellularCall = random.nextInt(100) > 1,
                projection = evidence(), targetHfpConnected = evidence(),
                targetAvailable = evidence(), endpointRevision = it.toLong() + 1, route = route)
            val before = p.phase
            val d = p.evaluate(s)
            transitions++
            check(p.requests <= if (manual) 1 else 3)
            if (before in terminal) check(!d.requestTarget)
            if (d.requestTarget) {
                check(s.authorized && s.active && s.singleCall && s.safeCellularCall &&
                    s.targetHfpConnected == true && s.targetAvailable == true)
                check(manual || (s.enabled && s.projection == true))
                check(s.now < 15_500)
                check(lastRequest == null || s.now - lastRequest!! >= 2_500)
                lastRequest = s.now
                requests++
            }
            d.wakeAt?.let { check(it > s.now) }
        }
    }
    println("PASS 5000 seeded policy traces, $transitions evaluated transitions, $requests permitted requests")
    println("Seed: 20260917; these are pure Kotlin policy checks, not Android or Bluetooth tests.")
}
