package dev.zhenlong.reader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderFont(val label: String) { PUBLISHER("默认"), SERIF("衬线"), SANS("无衬线"), CUSTOM("") }
/** 文字书排版，全局生效（SPEC §5.4）。除字体外都是滑块上的档位。 */
data class ReaderStyle(
    val fontSizeStep: Int = DEFAULT_FONT_STEP,
    val font: ReaderFont = ReaderFont.PUBLISHER,
    val customFont: String? = null,          // font == CUSTOM 时：字体文件夹里的文件名
    val lineHeightStep: Int = DEFAULT_LINE_STEP,
    val marginStep: Int = DEFAULT_MARGIN_STEP,
    val verticalMarginStep: Int = DEFAULT_VERTICAL_MARGIN_STEP,
) {
    val fontScale: Double get() = FONT_SCALES[fontSizeStep]
    val lineHeight: Double get() = LINE_MIN + lineHeightStep * LINE_STEP_SIZE

    /** Readium 的 pageMargins 倍数（左右边距）。 */
    val pageMargins: Double get() = marginStep * MARGIN_STEP_SIZE

    /** 正文距屏幕上缘的留白，dp。 */
    val verticalMarginDp: Int get() = verticalMarginStep * VERTICAL_MARGIN_STEP_DP

    companion object {
        private val FONT_SCALES = doubleArrayOf(0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.65, 1.8, 2.0, 2.2)
        val FONT_STEPS = FONT_SCALES.size
        const val DEFAULT_FONT_STEP = 2        // 1.0

        const val LINE_MIN = 1.0
        const val LINE_STEP_SIZE = 0.1
        const val LINE_STEPS = 13              // 1.0 … 2.2
        const val DEFAULT_LINE_STEP = 5        // 1.5

        const val MARGIN_STEP_SIZE = 0.25
        const val MARGIN_STEPS = 13            // 0 … 3.0；0 给漫画用，图片铺满
        const val DEFAULT_MARGIN_STEP = 4      // 1.0

        const val VERTICAL_MARGIN_STEP_DP = 8
        const val VERTICAL_MARGIN_STEPS = 9    // 0 … 64dp
        const val DEFAULT_VERTICAL_MARGIN_STEP = 3   // 24dp

        /** 漫画模式的默认：四边不留白，尽量多显示图。 */
        val MANGA_DEFAULT = ReaderStyle(marginStep = 0, verticalMarginStep = 0)
    }
}

data class Settings(
    val libraryRootUri: String? = null,
    val fontsFolderUri: String? = null,
    val readerStyle: ReaderStyle = ReaderStyle(),                 // 文字模式
    val mangaStyle: ReaderStyle = ReaderStyle.MANGA_DEFAULT,      // 漫画模式，各存各的
) {
    fun styleFor(kind: BookKind) = if (kind == BookKind.MANGA) mangaStyle else readerStyle
}

private val Context.dataStore by preferencesDataStore("settings")

class Prefs(private val context: Context) {
    private val libraryRootUri = stringPreferencesKey("libraryRootUri")
    private val fontsFolderUri = stringPreferencesKey("fontsFolderUri")

    /** 一套排版的键；漫画模式的键带 `manga.` 前缀。 */
    private class StyleKeys(prefix: String) {
        val fontSizeStep = intPreferencesKey("${prefix}fontSizeStep")
        val fontFamily = stringPreferencesKey("${prefix}fontFamily")
        val customFont = stringPreferencesKey("${prefix}customFont")
        val lineHeightStep = intPreferencesKey("${prefix}lineHeightStep")
        val hMarginStep = intPreferencesKey("${prefix}hMarginStep")
        val verticalMarginStep = intPreferencesKey("${prefix}verticalMarginStep")
    }

    private val textKeys = StyleKeys("")
    private val mangaKeys = StyleKeys("manga.")

    // 旧键：档位 n 表示 0.25 + 0.25n，新键从 0 起，读旧值时 +1
    private val legacyMarginStep = intPreferencesKey("marginStep")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            libraryRootUri = p[libraryRootUri],
            fontsFolderUri = p[fontsFolderUri],
            readerStyle = p.style(textKeys, ReaderStyle(), legacyMargin = p[legacyMarginStep]?.plus(1)),
            mangaStyle = p.style(mangaKeys, ReaderStyle.MANGA_DEFAULT),
        )
    }

    private fun Preferences.style(k: StyleKeys, d: ReaderStyle, legacyMargin: Int? = null) = ReaderStyle(
        fontSizeStep = (this[k.fontSizeStep] ?: d.fontSizeStep).coerceIn(0, ReaderStyle.FONT_STEPS - 1),
        font = enumOr(this[k.fontFamily], d.font),
        customFont = this[k.customFont],
        lineHeightStep = (this[k.lineHeightStep] ?: d.lineHeightStep).coerceIn(0, ReaderStyle.LINE_STEPS - 1),
        marginStep = (this[k.hMarginStep] ?: legacyMargin ?: d.marginStep).coerceIn(0, ReaderStyle.MARGIN_STEPS - 1),
        verticalMarginStep = (this[k.verticalMarginStep] ?: d.verticalMarginStep).coerceIn(0, ReaderStyle.VERTICAL_MARGIN_STEPS - 1),
    )

    suspend fun setLibraryRootUri(uri: String) = context.dataStore.edit { it[libraryRootUri] = uri }

    suspend fun setFontsFolderUri(uri: String) = context.dataStore.edit { it[fontsFolderUri] = uri }

    suspend fun setReaderStyle(kind: BookKind, style: ReaderStyle) = context.dataStore.edit {
        val k = if (kind == BookKind.MANGA) mangaKeys else textKeys
        it[k.fontSizeStep] = style.fontSizeStep
        it[k.fontFamily] = style.font.name
        it[k.lineHeightStep] = style.lineHeightStep
        it[k.hMarginStep] = style.marginStep
        it[k.verticalMarginStep] = style.verticalMarginStep
        style.customFont?.let { name -> it[k.customFont] = name } ?: it.remove(k.customFont)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
