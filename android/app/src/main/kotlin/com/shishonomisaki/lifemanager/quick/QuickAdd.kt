package com.shishonomisaki.lifemanager.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.MainActivity
import com.shishonomisaki.lifemanager.R
import com.shishonomisaki.lifemanager.ui.AppViewModel
import com.shishonomisaki.lifemanager.ui.components.QuickInputBar
import com.shishonomisaki.lifemanager.ui.components.QuickPreview
import com.shishonomisaki.lifemanager.ui.theme.LifeTheme

/** 桌面小工具和快速設定磚打開的輸入框，送出後就關掉。 */
class QuickAddActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!(application as LifeApp).repository.isSignedIn) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val voice = intent.getBooleanExtra(EXTRA_VOICE, false)
        setContent {
            LifeTheme {
                Box(
                    Modifier.fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, null) { finish() }
                        .imePadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {},
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    ) {
                        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                            Text("新增待辦", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                            QuickInputBar(
                                onSubmit = { text, kind -> vm.quickAdd(text, kind); finish() },
                                preview = QuickPreview(
                                    chips = { text, skip -> vm.previewChips(text, skip) },
                                    submit = { text, kind, skip -> vm.quickAdd(text, kind, skip = skip); finish() },
                                ),
                                autoFocus = !voice,
                                startVoice = voice,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_VOICE = "voice"

        fun intent(context: Context) = Intent(context, QuickAddActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/** 快速設定磚：下拉通知列一點就新增。 */
class QuickAddTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = QuickAddActivity.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** 桌面小工具：一條「＋ 新增待辦…」。 */
class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(context, 1, QuickAddActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)
        val views = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            setOnClickPendingIntent(R.id.widget_root, pi)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
