package app.deepseek.harness.mobile
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
internal interface CredentialCodec {fun encrypt(bytes:ByteArray):ByteArray;fun decrypt(bytes:ByteArray):ByteArray}
internal sealed class SavedConnection {
 abstract val origin:String
 class Direct(override val origin:String):SavedConnection()
 class Bridge(val profile:BridgeConnection):SavedConnection(){override val origin=profile.origin}
 override fun toString()=origin
}
internal class ConnectionStore(private val directory:File,private val codec:CredentialCodec) {
 private fun file(origin:String,suffix:String):File {val normalized=ServerConfig.normalize(origin);val hash=MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString(""){"%02x".format(it)};return File(directory,"$hash.$suffix")}
 fun isPrivate(origin:String):Boolean= synchronized(lock) {val metadata=file(origin,"mode");file(origin,"enc").exists() || (metadata.exists() && metadata.readText()!="direct")}
 fun load(origin:String):SavedConnection?= synchronized(lock) {
  val encrypted=file(origin,"enc");val mode=file(origin,"mode")
  val modeValue=if(mode.exists())mode.readText() else null
  if(modeValue!=null && modeValue !in setOf("direct","bridge","forgotten"))throw BridgeAuthenticationRequired()
  if(modeValue=="forgotten")return@synchronized null
  if(modeValue=="direct")return@synchronized SavedConnection.Direct(ServerConfig.normalize(origin))
  if(!encrypted.exists()){if(mode.exists() && mode.readText()=="bridge")throw BridgeAuthenticationRequired();return@synchronized null}
  try {val json=JSONObject(String(codec.decrypt(encrypted.readBytes()),Charsets.UTF_8));require(json.getInt("schemaVersion")==1);require(json.getString("origin")==ServerConfig.normalize(origin));val profile=BridgePairingClient.decode(origin,json);val p=BridgeConnection(profile.origin,profile.deviceId,profile.token,profile.rpcPath,profile.streamPath,profile.exportPath,profile.protocolVersion,json.optString("pairPath").takeIf{it.isNotBlank()&&it!="null"},json.optString("deviceName","Android"));SavedConnection.Bridge(p)}catch(e:Exception){throw BridgeAuthenticationRequired()}
 }
 fun save(connection:SavedConnection)= synchronized(lock) {
  directory.mkdirs()
  when(connection){is SavedConnection.Direct->{atomic(file(connection.origin,"mode"),"direct".toByteArray());file(connection.origin,"enc").delete();Unit}
   is SavedConnection.Bridge->{val json=connection.profile.json().put("schemaVersion",1);val cipher=codec.encrypt(json.toString().toByteArray(Charsets.UTF_8));atomic(file(connection.origin,"mode"),"bridge".toByteArray());atomic(file(connection.origin,"enc"),cipher)}}
 }
 fun remove(origin:String)= synchronized(lock) {directory.mkdirs();atomic(file(origin,"mode"),"forgotten".toByteArray());file(origin,"enc").delete();Unit}
 private fun atomic(destination:File,bytes:ByteArray) {val tmp=File.createTempFile("profile-",".tmp",directory);try{FileOutputStream(tmp).use{it.write(bytes);it.fd.sync()};check(tmp.renameTo(destination)){"Could not save connection"}}finally{tmp.delete()}}
 companion object {private val lock=Any();fun forContext(context:Context)=ConnectionStore(File(context.noBackupFilesDir,"connections"),KeystoreCodec())}
}
private class KeystoreCodec:CredentialCodec {
 private fun key(create:Boolean):SecretKey=synchronized(keyLock){val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val old=ks.getKey(alias,null) as? SecretKey;if(old!=null)return@synchronized old;if(!create)throw BridgeAuthenticationRequired();KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())}.generateKey()}
 override fun encrypt(bytes:ByteArray):ByteArray {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(true));return byteArrayOf(1)+c.iv+c.doFinal(bytes)}
 override fun decrypt(bytes:ByteArray):ByteArray {require(bytes.size>29 && bytes[0]==1.toByte());val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(1,13)));return c.doFinal(bytes.copyOfRange(13,bytes.size))}
 companion object {private const val alias="harness.private-server.credentials.v1";private val keyLock=Any()}
}
internal fun transportFor(context:Context,origin:String):HarnessTransport {
 val store=ConnectionStore.forContext(context)
 return when(val profile=store.load(origin)){is SavedConnection.Bridge->BridgeHarnessTransport(profile.profile);is SavedConnection.Direct->DirectHarnessTransport(origin,CloudflareOAuth(context).accessToken(origin),android.webkit.CookieManager.getInstance().getCookie(origin));null->{if(store.isPrivate(origin))throw BridgeAuthenticationRequired();DirectHarnessTransport(origin,CloudflareOAuth(context).accessToken(origin),android.webkit.CookieManager.getInstance().getCookie(origin))}}
}
