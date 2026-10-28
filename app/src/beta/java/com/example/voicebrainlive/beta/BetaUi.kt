package com.example.voicebrainlive.beta

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.voicebrainlive.ThemeUtils

/** Insets are owned by the activity content, once; child screens never guess status-bar heights. */
object BetaUi {
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density + .5f).toInt()
    fun installInsets(a: Activity) {
        WindowCompat.setDecorFitsSystemWindows(a.window, false)
        WindowCompat.getInsetsController(a.window,a.window.decorView).apply {
            isAppearanceLightStatusBars = ThemeUtils.getTheme(a)=="light"
            isAppearanceLightNavigationBars = ThemeUtils.getTheme(a)=="light"
        }
        val content=a.findViewById<ViewGroup>(android.R.id.content)
        content.clipToPadding=true
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom))
            view.findViewWithTag<View>("beta_primary_nav")?.visibility=if(ime.bottom>bars.bottom)View.GONE else View.VISIBLE
            insets // Children do not add system insets; retain them for popup/IME consumers.
        }
        ViewCompat.requestApplyInsets(content)
    }
    fun text(c: Context, value: String, size: Float=16f) = TextView(c).apply {
        text=value; textSize=size; isSaveEnabled=false
        includeFontPadding=true; setLineSpacing(dp(c,2).toFloat(),1.1f)
        typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL)
        setPadding(0,dp(c,6),0,dp(c,6))
    }
    fun button(c: Context, value: String, marker: String, action:()->Unit) = Button(c).apply {
        text=value; tag=marker; contentDescription=value; isAllCaps=false; textSize=14f
        includeFontPadding=true; setLineSpacing(dp(c,2).toFloat(),1.05f)
        minHeight=dp(c,48); minimumWidth=0; setSingleLine(false)
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(c,4);bottomMargin=dp(c,4)}
        setOnClickListener { action() }
    }
    fun page(a: Activity, title: String): LinearLayout {
        val outer=FrameLayout(a)
        val scroll=ScrollView(a).apply {isFillViewport=true; clipToPadding=false}
        val body=LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,16)); isSaveEnabled=false
        }
        scroll.addView(body)
        // A bounded reading column expands on phones and remains comfortable on tablets.
        outer.addView(scroll,FrameLayout.LayoutParams(minOf(a.resources.displayMetrics.widthPixels,dp(a,760)),-1,android.view.Gravity.CENTER_HORIZONTAL))
        body.addView(header(a,title))
        a.setContentView(outer)
        return body
    }
    fun header(a:Activity,title:String)=LinearLayout(a).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
        addView(ImageButton(a).apply{
            tag="beta_back";contentDescription="Back";setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            background=null;setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12))
            val color=android.util.TypedValue();a.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface,color,true);setColorFilter(color.data)
            setOnClickListener{a.finish()}
        },LinearLayout.LayoutParams(dp(a,48),dp(a,48)))
        addView(text(a,title,20f),LinearLayout.LayoutParams(0,-2,1f))
    }
    fun open(a: Activity, type: Class<*>)=a.startActivity(Intent(a,type).setPackage(a.packageName))
}

class BetaNotionActivity:AppCompatActivity(){
    override fun onCreate(state:Bundle?){ThemeUtils.applyTheme(this);super.onCreate(state)
        val p=BetaUi.page(this,"Notion")
        p.addView(BetaUi.text(this,"Not connected" ,18f))
        p.addView(BetaUi.text(this,"Your local tasks work without Notion. No Notion account or credential has been copied into BETA."))
        p.addView(BetaUi.text(this,"Read-only access needs a separately approved account, selected profile and task-property mapping. Pending/Synced describes synchronization, not task completion."))
        p.addView(BetaUi.button(this,"Connection requirements","beta_notion_requirements"){
            android.app.AlertDialog.Builder(this).setTitle("Before connecting Notion")
                .setMessage("Select one profile and permitted pages. Review exactly what text may be read. Confirm which property means completed. Approve a read-only pilot. Writes and background requests stay disabled.")
                .setPositiveButton("Close",null).show()
        })
    }
}
