package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
import java.net.ServerSocket
import java.nio.file.Files
class SessionExportWriterTest {
 @Test fun abortedChunkedExportDeletesPartialDestination(){
  val dir=Files.createTempDirectory("private-export-test").toFile();val file=java.io.File(dir,"session.zip")
  try{ServerSocket(0).use { server ->
   val worker=Thread{server.accept().use{socket ->
    val reader=socket.getInputStream().bufferedReader();var length=0
    while(true){val line=reader.readLine();if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()};repeat(length){reader.read()}
    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n3\r\nZIP\r\n".toByteArray())
   }};worker.start()
   val profile=BridgeConnection("http://127.0.0.1:${server.localPort}","device","secret","/a/b","/c/d","/e/f",1)
   assertThrows(java.io.IOException::class.java){writeSessionExport(BridgeHarnessTransport(profile),"session",{file.outputStream()},{file.delete();Unit})}
   assertFalse(file.exists());worker.join(2000)
  }}finally{dir.deleteRecursively()}
 }
}
