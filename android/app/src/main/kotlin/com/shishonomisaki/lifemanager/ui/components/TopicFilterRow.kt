package com.shishonomisaki.lifemanager.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.ui.theme.TopicColors

/** 「全部／工作／家庭／個人／知識」一排膠囊，今天頁和全部頁共用同一個選擇。[extra] 接在後面放其他篩選。 */
@Composable
fun TopicFilterRow(
    topics: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部") })
        topics.forEach { t ->
            val color = TopicColors.of(t)
            FilterChip(
                selected = selected == t,
                onClick = { onSelect(if (selected == t) null else t) },
                label = { Text(t) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = color,
                    selectedContainerColor = color.copy(alpha = 0.18f),
                    selectedLabelColor = color,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = selected == t,
                    borderColor = color.copy(alpha = 0.5f), selectedBorderColor = color,
                ),
            )
        }
        extra()
    }
}
