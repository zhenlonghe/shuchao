package dev.zhenlong.reader.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

private val InkColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color.Black,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
)

/** 按压无任何视觉反馈（SPEC §2.1）。 */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = -1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InkColors) {
        CompositionLocalProvider(
            LocalIndication provides NoIndication,
            LocalRippleConfiguration provides null,
            content = content,
        )
    }
}

/**
 * 让开系统栏，但按「栏始终在」来算：从阅读器（全屏）退回来时状态栏是稍后才重新出现的，
 * 跟着实际可见性走的话，整页会先顶到屏幕最上面、再被推下来——墨水屏上多一次整屏刷新。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.stableSystemBarsPadding(): Modifier = windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)

/** 1 物理像素。 */
val OnePx: Dp
    @Composable get() = with(LocalDensity.current) { 1.toDp() }
