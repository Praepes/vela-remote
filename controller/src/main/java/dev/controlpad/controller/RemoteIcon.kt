package dev.controlpad.controller

import android.graphics.*
import android.graphics.drawable.Drawable

/** 与控件共同缩放的矢量图标，24 单位描边网格。 */
class RemoteIcon(private val name:String,private val tint:Int,private val pixels:Int):Drawable() {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint; style=Paint.Style.STROKE; strokeWidth=1.8f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    override fun getIntrinsicWidth()=pixels
    override fun getIntrinsicHeight()=pixels
    override fun draw(c:Canvas) {
        val save=c.save(); c.translate(bounds.left.toFloat(),bounds.top.toFloat()); c.scale(bounds.width()/24f,bounds.height()/24f)
        fun path(vararg points:Float) { val p=Path(); p.moveTo(points[0],points[1]); var i=2; while(i<points.size) { p.lineTo(points[i],points[i+1]); i+=2 }; c.drawPath(p,pen) }
        fun line(x:Float,y:Float,ex:Float,ey:Float)=c.drawLine(x,y,ex,ey,pen)
        when(name) {
            "back" -> path(15f,5f,8f,12f,15f,19f)
            "home" -> { path(3f,11f,12f,4f,21f,11f); path(6f,10f,6f,21f,10f,21f,10f,15f,14f,15f,14f,21f,18f,21f,18f,10f) }
            "play" -> { path(4f,5f,13f,12f,4f,19f,4f,5f); line(18f,6f,18f,18f); line(22f,6f,22f,18f) }
            "minus","plus" -> { path(2f,9f,6f,9f,11f,5f,11f,19f,6f,15f,2f,15f,2f,9f); line(16f,12f,22f,12f); if(name=="plus") line(19f,9f,19f,15f) }
            "center" -> { path(3f,8f,3f,3f,8f,3f); path(16f,3f,21f,3f,21f,8f); path(21f,16f,21f,21f,16f,21f); path(8f,21f,3f,21f,3f,16f); c.drawCircle(12f,12f,3f,pen) }
            "settings" -> { c.drawCircle(12f,12f,7f,pen); c.drawCircle(12f,12f,2.5f,pen); repeat(8) { val angle=it*Math.PI/4; line((12+7*kotlin.math.cos(angle)).toFloat(),(12+7*kotlin.math.sin(angle)).toFloat(),(12+10*kotlin.math.cos(angle)).toFloat(),(12+10*kotlin.math.sin(angle)).toFloat()) } }
            "preview" -> { c.drawRoundRect(2f,4f,22f,18f,3f,3f,pen); line(9f,22f,15f,22f); line(12f,18f,12f,22f) }
            "up" -> path(5f,15f,12f,8f,19f,15f)
            "down" -> path(5f,9f,12f,16f,19f,9f)
            "left" -> path(15f,5f,8f,12f,15f,19f)
            "right" -> path(9f,5f,16f,12f,9f,19f)
            "confirm" -> { c.drawCircle(12f,12f,8f,pen); path(8f,12f,11f,15f,16f,9f) }
        }
        c.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int) { pen.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { pen.colorFilter=filter }
    @Deprecated("Android Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
