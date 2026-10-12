package app.deepseek.harness.mobile
import android.os.Handler
/** Worker callbacks keep their admitting profile; timers are removed by lifecycle owners. */
internal class ConnectionMainHandler(private val handler:Handler, private val wrap:(Runnable)->Runnable) {
 fun post(task:Runnable)=handler.post(wrap(task))
 fun postDelayed(task:Runnable,delay:Long)=handler.postDelayed(task,delay)
 fun removeCallbacks(task:Runnable)=handler.removeCallbacks(task)
 fun removeCallbacksAndMessages(token:Any?)=handler.removeCallbacksAndMessages(token)
}
