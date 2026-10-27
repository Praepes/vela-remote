package dev.controlpad.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import androidx.appcompat.widget.AppCompatButton

/** 图标和文字作为同一个内容块居中，避免顶部 compound drawable 贴近按钮上沿。 */
class RemoteKeyButton(context:Context):AppCompatButton(context) {
    var topIcon:Drawable?=null
        set(value) { field=value; invalidate() }

    init { gravity=Gravity.CENTER; includeFontPadding=false }

    override fun onDraw(canvas:Canvas) {
        val icon=topIcon ?: return super.onDraw(canvas)
        val iconSize=(23*resources.displayMetrics.density).toInt()
        val gap=4*resources.displayMetrics.density
        val fm=paint.fontMetrics
        val label=TextUtils.ellipsize(text,paint,(width-paddingLeft-paddingRight).coerceAtLeast(0).toFloat(),TextUtils.TruncateAt.END).toString()
        val contentHeight=iconSize+gap+fm.descent-fm.ascent
        val top=paddingTop+(height-paddingTop-paddingBottom-contentHeight)/2f
        val center=paddingLeft+(width-paddingLeft-paddingRight)/2f
        val save=canvas.save()
        canvas.translate(center-iconSize/2f,top)
        icon.setBounds(0,0,iconSize,iconSize); icon.draw(canvas)
        canvas.restoreToCount(save)
        paint.color=currentTextColor; paint.textAlign=Paint.Align.CENTER
        canvas.drawText(label,center,top+iconSize+gap-fm.ascent,paint)
    }
}
