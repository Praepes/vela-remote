package dev.controlpad.receiver

import android.app.Activity
import android.os.Bundle
import android.view.*
import android.widget.*
import dev.controlpad.shared.Ui
import org.json.JSONObject

/** 可观察输入事件、滚动位置、缩放和拖动结果的本地验收页。 */
class InputLabActivity:Activity() {
    private var touches=0; private var multiEvents=0; private var cancellations=0
    private val actions=mutableListOf<Int>()
    private val results=JSONObject().put("clicks",0).put("longPresses",0).put("scale",1.0).put("drag",0).put("scroll",0)
    private fun metric(key:String,value:Any) { results.put(key,value); if(BuildConfig.DEBUG) java.io.File(filesDir,"lab-results.json").writeText(results.toString()) }
    override fun dispatchTouchEvent(e:MotionEvent):Boolean {
        touches++; if(e.pointerCount>1) multiEvents++; if(e.actionMasked==MotionEvent.ACTION_CANCEL) cancellations++
        actions.add(e.actionMasked); if(actions.size>100) actions.removeAt(0)
        if(BuildConfig.DEBUG) java.io.File(filesDir,"lab-events.json").writeText(JSONObject().put("touches",touches).put("multi",multiEvents).put("cancels",cancellations).put("lastX",e.x).put("lastY",e.y).put("actions",org.json.JSONArray(actions)).toString())
        return super.dispatchTouchEvent(e)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val ui=Ui(this); val body=ui.column().apply { setPadding(24,12,24,12); setBackgroundColor(ui.background) }
        val title=ui.text("输入验证 · 点击／滚动／缩放／拖动",21f,true); body.addView(title)
        val result=ui.text("等待输入",14f).apply { contentDescription="input_result" }; body.addView(result)
        val stage=FrameLayout(this).apply { setBackgroundColor(ui.surface) }
        val target=ui.button("点击 0 次",true) {}.apply { contentDescription="click_target" }
        var clicks=0; var down=0f; var base=0f; var drag=false
        target.setOnClickListener { clicks++; target.text="点击 $clicks 次"; result.text="点击=$clicks"; metric("clicks",clicks) }
        target.setOnLongClickListener { drag=true; result.text="长按已识别"; metric("longPresses",results.getInt("longPresses")+1); true }
        target.setOnTouchListener { v,e->
            if(e.actionMasked==MotionEvent.ACTION_DOWN) { down=e.rawX; base=v.translationX; drag=false }
            if(e.actionMasked==MotionEvent.ACTION_MOVE && kotlin.math.abs(e.rawX-down)>8) { v.translationX=base+e.rawX-down; result.text="拖动=${v.translationX.toInt()}"; metric("drag",v.translationX.toInt()) }
            false
        }
        stage.addView(target,FrameLayout.LayoutParams(230,70).apply { leftMargin=100; topMargin=50 })
        var factor=1f; var multi=0
        val scale=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(d:ScaleGestureDetector):Boolean { factor=(factor*d.scaleFactor).coerceIn(0.3f,3f); target.scaleX=factor; target.scaleY=factor; result.text="缩放=%.2f  双指=%d".format(factor,multi); metric("scale",factor.toDouble()); return true } })
        stage.setOnTouchListener { _,e-> if(e.pointerCount==2) multi++; scale.onTouchEvent(e); result.contentDescription="scale=$factor;multi=$multi;action=${e.actionMasked}"; true }
        body.addView(stage,LinearLayout.LayoutParams(-1,200))
        val scroll=ScrollView(this).apply { contentDescription="scroll_list" }; val items=ui.column()
        repeat(60) { i->items.addView(ui.button("方向导航与滚动测试 · 第 ${i+1} 项") { result.text="已选择第 ${i+1} 项" },LinearLayout.LayoutParams(-1,62)) }
        scroll.addView(items); scroll.setOnScrollChangeListener { _,_,y,_,_->result.text="滚动位置=$y"; metric("scroll",y) }; body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(body)
    }
}
