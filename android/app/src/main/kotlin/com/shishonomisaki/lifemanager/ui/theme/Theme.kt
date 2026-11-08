package com.shishonomisaki.lifemanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shishonomisaki.lifemanager.LifeApp

// 品牌色：墨綠，跟桌布換色關掉時用
private val Light = lightColorScheme(
    primary = Color(0xFF2E6B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBDB),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DE),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF436278),
    tertiaryContainer = Color(0xFFC9E6FF),
    onTertiaryContainer = Color(0xFF001E2F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAF7),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FAF7),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5F1),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE3EAE6),
    surfaceContainerHighest = Color(0xFFDEE4E0),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CD3C2),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF125145),
    onPrimaryContainer = Color(0xFFB8EBDB),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8DE),
    tertiary = Color(0xFFAACBE4),
    tertiaryContainer = Color(0xFF2B4A5F),
    onTertiaryContainer = Color(0xFFC9E6FF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B100E),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0B100E),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    // 卡片跟背景拉開，深色模式下比較看得出一張一張
    surfaceContainerLow = Color(0xFF1E2724),
    surfaceContainer = Color(0xFF232C29),
    surfaceContainerHigh = Color(0xFF2B3431),
    surfaceContainerHighest = Color(0xFF353E3B),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4945),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 字型層級：頁面大標題、分區標題、內文、輔助文字分清楚。中文字稍微加粗比較好讀。 */
private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** 待確認（AI 低信心）的底色。 */
val ReviewYellow = Color(0xFFFFF4C2)
val ReviewYellowDark = Color(0xFF4A4120)
val OverdueRed = Color(0xFFC62828)

/** 四個主題各一個顏色，一眼分出工作還是家庭。 */
object TopicColors {
    private val light = mapOf(
        "工作" to Color(0xFF1E66C9),
        "家庭" to Color(0xFFE0700F),
        "個人" to Color(0xFF2E8B57),
        "知識" to Color(0xFF7E57C2),
    )
    private val dark = mapOf(
        "工作" to Color(0xFF8AB8FF),
        "家庭" to Color(0xFFFFB37A),
        "個人" to Color(0xFF8FD6A8),
        "知識" to Color(0xFFC6A8FF),
    )

    @Composable
    fun of(topic: String?): Color {
        val map = if (isSystemInDarkTheme()) dark else light
        return topic?.let { map[it] } ?: MaterialTheme.colorScheme.primary
    }
}

@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by (context.applicationContext as LifeApp).settings.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
