package com.winlator.cmod.core

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Shown right after the main screens crash, in its own process (":crash"): the report used to wait
 * for the next launch, and a crash at start meant it was never seen. Plain views, no app theme or
 * strings, so whatever broke the app can't break this screen too.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ru = Locale.getDefault().language == "ru"
        val text = intent.getStringExtra(EXTRA_TEXT)
            ?: runCatching { File(filesDir, "crash/last-crash.txt").readText() }.getOrNull()
            ?: "—"
        val pad = (16 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            setText(if (ru) "YurNLator аварийно закрылся" else "YurNLator crashed")
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val hint = TextView(this).apply {
            setText(if (ru) "Скопируйте или отправьте этот отчёт разработчику." else "Copy or share this report with the developer.")
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 4, 0, pad / 2)
        }
        val body = TextView(this).apply {
            setText(text)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(0xE6, 0xE6, 0xE6))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(body) }

        fun button(label: String, click: () -> Unit) = Button(this).apply {
            setText(label)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(if (ru) "Копировать" else "Copy") {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("YurNLator crash", text))
                Toast.makeText(this@CrashActivity, if (ru) "Скопировано" else "Copied", Toast.LENGTH_SHORT).show()
            })
            addView(button(if (ru) "Поделиться" else "Share") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), null))
            })
            addView(button(if (ru) "Перезапустить" else "Restart") {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                finish()
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x10, 0x11, 0x14))
            setPadding(pad, pad * 2, pad, pad)
            addView(title)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        })
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
