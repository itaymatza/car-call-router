import com.itaymatza.carcallrouter.core.PolicyCases
fun main() {
    var failed = 0
    PolicyCases.cases().forEach { (name, body) ->
        try { body(); println("PASS $name") }
        catch (e: Throwable) { failed++; println("FAIL $name: ${e.message}") }
    }
    println("${PolicyCases.cases().size - failed}/${PolicyCases.cases().size} passed")
    check(failed == 0) { "$failed policy checks failed" }
}
