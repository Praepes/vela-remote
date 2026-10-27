package dev.controlpad.shared

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*

class Ui(val context:Context,val dark:Boolean=false) {
    val background=Color.parseColor(if(dark) "#11151D" else "#F4F5F8")
    val surface=Color.parseColor(if(dark) "#202733" else "#FFFFFF")
    val ink=Color.parseColor(if(dark) "#EFF3FA" else "#182234")
    val muted=Color.parseColor(if(dark) "#A4B0C3" else "#748094")
    val blue=Color.parseColor("#3269F5")
    fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun rounded(color:Int,radius:Int=20,stroke:Int=0)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat(); if(stroke!=0) setStroke(dp(1),stroke) }
    fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun row()=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    fun text(value:String,size:Float=15f,bold:Boolean=false,color:Int=ink)=TextView(context).apply {
        text=value; textSize=size; setTextColor(color); if(bold) setTypeface(typeface,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL
        setPadding(0,dp(4),0,dp(4))
    }
    fun card(content:View)=FrameLayout(context).apply { background=rounded(surface); setPadding(dp(20),dp(16),dp(20),dp(16)); addView(content) }
    fun button(label:String,primary:Boolean=false,onClick:()->Unit)=comButton(label,primary).apply { setOnClickListener { onClick() } }
    private fun comButton(label:String,primary:Boolean)=styleButton(Button(context),label,primary)
    fun styleButton(button:Button,label:String,primary:Boolean=false)=button.apply {
        text=label; textSize=14f; isAllCaps=false; minHeight=dp(48); minimumHeight=dp(48); minWidth=0; minimumWidth=0
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,if(primary) Color.WHITE else ink)))
        background=RippleDrawable(ColorStateList.valueOf(if(primary) 0x32FFFFFF else 0x163269F5),rounded(if(primary) blue else surface,16),null)
        stateListAnimator=null; setPadding(dp(14),dp(4),dp(14),dp(4))
        maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        isFocusable=true
    }
    fun space(height:Int)=Space(context).apply { layoutParams=LinearLayout.LayoutParams(1,dp(height)) }
    fun input(hintText:String,value:String="")=EditText(context).apply { hint=hintText; setText(value); textSize=15f; setTextColor(ink); setHintTextColor(muted); isSingleLine=true; minHeight=dp(52) }
}
