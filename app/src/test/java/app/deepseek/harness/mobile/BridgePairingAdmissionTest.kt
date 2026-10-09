package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
class BridgePairingAdmissionTest {
 @Test fun cancelledOrChangedServerCannotAdmitLatePairing() {
  val admission=BridgePairingAdmission(4)
  assertTrue(admission.accepts(4))
  assertFalse(admission.accepts(5))
  admission.cancel()
  assertFalse(admission.accepts(4))
 }
}
