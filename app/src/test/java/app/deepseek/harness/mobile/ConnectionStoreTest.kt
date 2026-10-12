package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
class ConnectionStoreTest {
 private fun codec(): CredentialCodec {
  val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
  return object:CredentialCodec {
   override fun encrypt(bytes:ByteArray):ByteArray {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);return c.iv+c.doFinal(bytes)}
   override fun decrypt(bytes:ByteArray):ByteArray {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)));return c.doFinal(bytes.copyOfRange(12,bytes.size))}
  }
 }
 @Test fun protectedProfilePersistsAndOriginsAreIsolated() {
  val dir=Files.createTempDirectory("bridge-store").toFile();try {
   val crypt=codec();val store=ConnectionStore(dir,crypt)
   val profile=BridgeConnection("https://one.example","d","private-secret","/a/b","/c/d","/e/f",1)
   store.save(SavedConnection.Bridge(profile))
   assertNull(store.load("https://two.example"));assertTrue(ConnectionStore(dir,crypt).load("https://one.example") is SavedConnection.Bridge)
   assertFalse(dir.listFiles()!!.any{it.readText().contains("private-secret")})
   assertThrows(BridgeAuthenticationRequired::class.java){ConnectionStore(dir,codec()).load("https://one.example")}
   store.remove("https://one.example");assertNull(store.load("https://one.example"))
  } finally {dir.deleteRecursively()}
 }
 @Test fun corruptedCredentialsNeverBecomeDirect() {
  val dir=Files.createTempDirectory("bridge-store").toFile();try {val store=ConnectionStore(dir,codec());store.save(SavedConnection.Bridge(BridgeConnection("https://one.example","d","secret","/a/b","/c/d","/e/f",1)));dir.listFiles()!!.first().writeText("broken");assertThrows(BridgeAuthenticationRequired::class.java){store.load("https://one.example")}}finally{dir.deleteRecursively()}
 }
}
