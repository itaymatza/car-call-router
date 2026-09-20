import org.junit.Test

/** Runs the production-service callback suite under JUnit so JaCoCo can enforce real coverage. */
class ServiceHarnessTest {
    @Test
    fun productionServiceScenarios() {
        main(emptyArray())
    }
}
