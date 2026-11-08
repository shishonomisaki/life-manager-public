package com.shishonomisaki.lifemanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shishonomisaki.lifemanager.reminder.ReminderSyncWorker
import com.shishonomisaki.lifemanager.telegram.TelegramService
import com.shishonomisaki.lifemanager.ui.AppViewModel
import com.shishonomisaki.lifemanager.ui.LifeRoot
import com.shishonomisaki.lifemanager.ui.theme.LifeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private val openTaskId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTaskId.value = intent?.getStringExtra(EXTRA_TASK_ID)
        // APP 開著時用長輪詢收 Telegram 訊息，幾乎即時；離開畫面就停，交給背景每 15 分鐘收一次
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { TelegramService.get(this@MainActivity).runForeground() }
        }
        setContent {
            LifeTheme {
                Surface {
                    LifeRoot(vm, openTaskId.value, onOpenTaskHandled = { openTaskId.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TASK_ID)?.let { openTaskId.value = it }
    }

    override fun onResume() {
        super.onResume()
        // 提醒權限或時區可能變了，順便重排一次
        ReminderSyncWorker.runOnce(this)
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}
