package app.deepseek.harness.mobile
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.concurrent.Executor
internal class BridgePairingAdmission(private val generation:Long) {
 @Volatile private var active=true
 fun accepts(current:Long)=active && current==generation
 fun cancel(){active=false}
}
internal class BridgeSetupDialog(
 private val context:Context,private val palette:AppPalette,private val text:(String,String)->String,private val worker:Executor,
 private val generation:Long,private val currentGeneration:()->Long,
 private val existing:BridgeConnection?,private val initialConfig:String?,private val initialCode:String?,
 private val onConnected:(BridgeConnection)->Unit,private val onReconnect:()->Unit,private val onDirect:()->Unit,
 private val onConnections:()->Unit,private val onForget:()->Unit,
 private val onClosed:()->Unit = {},
) {
 private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
 private fun label(value:String)=TextView(context).apply{text=value;setTextColor(palette.muted);textSize=13f;setPadding(0,dp(12),0,dp(5))}
 private fun modeButton(label:String)=MaterialButton(context).apply{
  id=View.generateViewId();text=label;contentDescription=label;minHeight=dp(48)
  val states=arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf())
  backgroundTintList=ColorStateList(states,intArrayOf(palette.primaryButtonFill,palette.control))
  setTextColor(ColorStateList(states,intArrayOf(palette.primaryButtonText,palette.text)))
 }
 private fun field(label:String,multi:Boolean=false,secret:Boolean=false)=EditText(context).apply{
  hint=label;contentDescription=label;textSize=14f;setTextColor(palette.text);setHintTextColor(palette.muted)
  setPadding(dp(12),dp(10),dp(12),dp(10));minHeight=dp(48)
  inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or (if(multi)InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
  isSingleLine=!multi;if(multi){minLines=3;maxLines=5}
  if(secret){inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;transformationMethod=android.text.method.PasswordTransformationMethod.getInstance();isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS}
  background=GradientDrawable().apply{setColor(palette.control);cornerRadius=dp(10).toFloat();setStroke(dp(1),palette.border)}
 }
 fun show() {
  val admission=BridgePairingAdmission(generation);val main=Handler(Looper.getMainLooper())
  val configInput=field(text("连接资料","Connection configuration"),multi=true)
  val codeInput=field(text("一次性配对码","One-time pairing code"),secret=true)
  val nameInput=field(text("设备名称","Device name")).apply{setText(existing?.deviceName?:Build.MODEL)}
  if(existing?.pairPath!=null)configInput.setText(org.json.JSONObject().put("protocolVersion",1).put("origin",existing.origin).put("pairPath",existing.pairPath).toString())
  initialConfig?.let(configInput::setText);initialCode?.let(codeInput::setText)
  val status=label("").apply{visibility=View.GONE;setTextColor(palette.red);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
  val group=MaterialButtonToggleGroup(context).apply{isSingleSelection=true;isSelectionRequired=true}
  val privateButton=modeButton(text("私有服务","Private server"))
  val directButton=modeButton(text("直接连接","Direct connection"))
  group.addView(privateButton,LinearLayout.LayoutParams(0,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,1f));group.addView(directButton,LinearLayout.LayoutParams(0,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,1f));group.check(privateButton.id)
  // ToggleGroup initializes children as one-line labels; apply wrapping afterwards.
  listOf(privateButton,directButton).forEach{it.isSingleLine=false;it.maxLines=2;it.ellipsize=null}
  val pairButton=MaterialButton(context).apply{this.text=text("配对并连接","Pair and connect");minHeight=dp(48)}
  var reconnectButton: MaterialButton? = null
  val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(6),dp(20),dp(14));addView(group)
   addView(label(text("从服务端电脑复制连接资料和配对码。配对码十分钟内有效，成功使用一次后作废。","Copy the configuration and pairing code from the server computer. Codes expire after ten minutes and can be used once.")))
   if(existing!=null){addView(label(text("已保存的私有服务","Saved private server")));addView(TextView(context).apply{this.text="${existing.origin}\n${existing.deviceName}\n${text("访问凭据已安全保存","Access credentials saved securely")}";textSize=14f;setTextColor(palette.text);setPadding(0,dp(8),0,dp(8))})
    addView(MaterialButton(context).apply{this.text=text("使用已保存连接","Use saved connection")}.also{reconnectButton=it})
   }
   addView(label(text("连接资料","Connection configuration")));addView(configInput)
   addView(label(text("一次性配对码","One-time pairing code")));addView(codeInput)
   addView(label(text("设备名称","Device name")));addView(nameInput);addView(status);addView(pairButton)
  }
  val scroll=ScrollView(context).apply{isFillViewport=false;addView(body)}
  val title=TextView(context).apply{this.text=text("服务器连接","Server connection");setTextColor(palette.text);textSize=20f;setTypeface(null,Typeface.BOLD);setPadding(dp(20),dp(18),dp(20),dp(6))}
  val dialog=AlertDialog.Builder(context).setCustomTitle(title).setView(scroll)
   .setNegativeButton(text("取消","Cancel"),null).setNeutralButton(text("连接列表","Connection list")){_,_->onConnections()}.create()
  existing?.let{body.addView(MaterialButton(context).apply{this.text=text("清除此连接","Forget connection");setTextColor(palette.red);setOnClickListener{AlertDialog.Builder(context).setTitle(text("清除此连接？","Forget this connection?"))
   .setMessage(text("将删除手机保存的访问凭据。需要再次使用时，请重新配对。","This removes access credentials from this phone. Pair again to use this server later."))
   .setPositiveButton(text("清除","Forget")){_,_->dialog.dismiss();onForget()}.setNegativeButton(text("取消","Cancel"),null).show()}})}
  reconnectButton?.setOnClickListener{dialog.dismiss();onReconnect()}
  dialog.setOnDismissListener{admission.cancel();codeInput.text.clear();onClosed()}
  group.addOnButtonCheckedListener{_,id,checked->if(checked&&id==directButton.id){dialog.dismiss();onDirect()}}
  pairButton.setOnClickListener{
   val config=try{BridgePairingClient.parseConfig(configInput.text.toString())}catch(e:Exception){status.text=text("连接资料格式不正确，请重新从服务端复制。","Invalid connection configuration. Copy it again from the server.");status.visibility=View.VISIBLE;return@setOnClickListener}
   val code=codeInput.text.toString().trim();val name=nameInput.text.toString().trim()
   if(code.isBlank()||name.isBlank()){status.text=text("请输入配对码和设备名称。","Enter a pairing code and device name.");status.visibility=View.VISIBLE;return@setOnClickListener}
   listOf(configInput,codeInput,nameInput,privateButton,directButton,pairButton).forEach{it.isEnabled=false}
   pairButton.text=text("正在配对…","Pairing…");status.text=text("正在验证设备授权…","Verifying device access…");status.setTextColor(palette.muted);status.visibility=View.VISIBLE
   worker.execute{
    val result=runCatching{BridgePairingClient().pair(config.origin,config.pairPath,code,name)}
    main.post{
     if(!admission.accepts(currentGeneration()))return@post
     codeInput.text.clear()
     result.fold(onSuccess={profile->
      try {onConnected(profile);dialog.dismiss()}catch(e:Exception){status.text=text("无法安全保存访问凭据，请生成新配对码重试。","Could not save access credentials. Generate a new pairing code and try again.");status.setTextColor(palette.red);listOf(configInput,codeInput,nameInput,privateButton,directButton,pairButton).forEach{it.isEnabled=true};pairButton.text=text("重新配对","Pair again")}
     },onFailure={error->
      status.text=if(error is BridgeAuthenticationRequired)text("配对码无效或已使用，请在电脑上生成新码。","The code is invalid or already used. Generate a fresh code on the computer.") else text("未能完成配对。检查网络后，请在电脑上生成新配对码。","Pairing did not complete. Check the network and generate a fresh code on the computer.")
      status.setTextColor(palette.red);listOf(configInput,codeInput,nameInput,privateButton,directButton,pairButton).forEach{it.isEnabled=true};pairButton.text=text("重新配对","Pair again")
     })
    }
   }
  }
  dialog.show();dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  dialog.window?.setBackgroundDrawable(GradientDrawable().apply{setColor(palette.composer);cornerRadius=dp(18).toFloat()})
  dialog.window?.setLayout((context.resources.displayMetrics.widthPixels*0.94f).toInt(),android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
  val visibleFrame=Rect();val location=IntArray(2)
  scroll.viewTreeObserver.addOnGlobalLayoutListener{
   scroll.getWindowVisibleDisplayFrame(visibleFrame);scroll.getLocationOnScreen(location)
   val overlap=(location[1]+scroll.height-visibleFrame.bottom).coerceAtLeast(0)
   if(scroll.paddingBottom!=overlap){
    scroll.setPadding(0,0,0,overlap)
    if(overlap>0)dialog.window?.currentFocus?.let{focused->scroll.post{focused.requestRectangleOnScreen(Rect(0,0,focused.width,focused.height),true)}}
   }
  }
 }
}
