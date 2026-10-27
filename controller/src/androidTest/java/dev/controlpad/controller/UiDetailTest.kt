package dev.controlpad.controller

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiDetailTest {
    @Test fun disconnectedControlsDarkModeAndDirectionalButtons() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val prefs=activity.getSharedPreferences("settings",0); val original=prefs.getBoolean("dark",false)
        fun screenshot(name:String) { SystemClock.sleep(500); inst.uiAutomation.takeScreenshot()?.let { bitmap->File(activity.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() } }
        try {
            onView(withText("返回")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withText("查看辅助画面")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            screenshot("home-details.png")
            onView(withContentDescription("设置")).perform(click())
            onView(withText("深色界面")).perform(click()); onView(withText("完成")).perform(click())
            assertEquals(!original,prefs.getBoolean("dark",original))
            screenshot("home-dark.png")
            onView(withText("方向键")).perform(click())
            val buttons=mutableListOf<View>()
            inst.runOnMainSync {
                fun walk(v:View) { if(v is Button && v.contentDescription?.toString() in listOf("上","下","左","右","确认")) buttons.add(v); if(v is ViewGroup) repeat(v.childCount) { walk(v.getChildAt(it)) } }
                walk(activity.window.decorView)
            }
            assertEquals(5,buttons.size)
            buttons.forEach { assertTrue("方向键触摸区域小于48dp",it.width>=48*activity.resources.displayMetrics.density && it.height>=48*activity.resources.displayMetrics.density) }
            screenshot("direction-details.png")
        } finally {
            if(prefs.getBoolean("dark",false)!=original) { onView(withContentDescription("设置")).perform(click()); onView(withText("深色界面")).perform(click()); onView(withText("完成")).perform(click()) }
            inst.runOnMainSync { activity.finish() }
        }
    }
}
