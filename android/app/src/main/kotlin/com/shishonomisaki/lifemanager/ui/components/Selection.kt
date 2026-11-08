package com.shishonomisaki.lifemanager.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** 多選：長按一筆待辦開始選，之後點一下加入或取消；全部取消就結束。 */
class Selection {
    var ids by mutableStateOf(emptySet<String>())
        private set
    val active: Boolean get() = ids.isNotEmpty()

    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun clear() { ids = emptySet() }
}

/** 清單畫面裡的待辦卡片從這裡拿多選狀態；沒提供（例如編輯畫面的子任務）就不能多選。 */
val LocalSelection = staticCompositionLocalOf<Selection?> { null }
