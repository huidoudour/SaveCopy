package app.rikka.savecopy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The values the XML layouts used to pull straight out of the platform theme:
 * dialog padding, text appearances, text colours and the list divider.
 *
 * Everything is resolved from the current activity theme so the Compose
 * screens keep following the system light/dark theme (including vendor
 * overlays such as MIUI/HyperOS) exactly like the old XML did.
 */
class PlatformTheme(
    val dialogPreferredPadding: Dp,
    val textColorPrimary: Color,
    val textColorSecondary: Color,
    val defaultTextColor: Color,
    val defaultTextColorDisabled: Color,
    val colorControlHighlight: Color,
    val listItemTextStyle: TextStyle,
    val listItemTextColorDisabled: Color,
    val mediumTextStyle: TextStyle,
    val defaultTextStyle: TextStyle,
)

@Composable
fun rememberPlatformTheme(): PlatformTheme {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density.density, density.fontScale) {
        buildPlatformTheme(context, density)
    }
}

private fun buildPlatformTheme(context: Context, density: Density): PlatformTheme {
    val defaultTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    // TextView's default text colour is theme dependent (AOSP dialogs use the
    // secondary colour, vendor themes differ); ask a real TextView instead of
    // assuming textColorPrimary.
    val textProbe = TextView(context)
    val defaultTextColor = Color(textProbe.currentTextColor)
    textProbe.isEnabled = false
    val defaultTextColorDisabled = Color(textProbe.currentTextColor)

    return PlatformTheme(
        dialogPreferredPadding = with(density) {
            context.resolveDimension(android.R.attr.dialogPreferredPadding).toDp()
        },
        textColorPrimary = context.resolveColor(android.R.attr.textColorPrimary),
        textColorSecondary = context.resolveColor(android.R.attr.textColorSecondary),
        defaultTextColor = defaultTextColor,
        defaultTextColorDisabled = defaultTextColorDisabled,
        colorControlHighlight = context.resolveColor(android.R.attr.colorControlHighlight),
        listItemTextStyle = context.resolveTextAppearance(
            android.R.attr.textAppearanceListItem, density,
        ),
        listItemTextColorDisabled = context.resolveTextAppearanceDisabledColor(
            android.R.attr.textAppearanceListItem,
        ),
        mediumTextStyle = context.resolveTextAppearance(
            android.R.attr.textAppearanceMedium, density, bold = true,
        ),
        defaultTextStyle = TextStyle(
            color = defaultTextColor,
            fontSize = 14.sp,
            fontFamily = FontFamily(defaultTypeface),
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        ),
    )
}

/**
 * Resolves a text appearance attribute (for example
 * {@code ?android:attr/textAppearanceListItem}) into a Compose style.
 */
@SuppressLint("ResourceType", "WrongConstant")
private fun Context.resolveTextAppearance(
    @AttrRes attr: Int,
    density: Density,
    bold: Boolean = false,
): TextStyle {
    val styleAttributes = obtainStyledAttributes(intArrayOf(attr))
    val styleRes = try {
        styleAttributes.getResourceId(0, 0)
    } finally {
        styleAttributes.recycle()
    }
    val defaultSizePx = with(density) { 14.sp.toPx().toInt() }
    if (styleRes == 0) {
        return TextStyle(
            color = resolveColor(android.R.attr.textColorPrimary),
            fontSize = with(density) { defaultSizePx.toSp() },
            fontFamily = FontFamily(Typeface.create("sans-serif", Typeface.NORMAL)),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        )
    }

    val appearance = obtainStyledAttributes(
        styleRes,
        intArrayOf(
            android.R.attr.textSize,
            android.R.attr.textColor,
            android.R.attr.fontFamily,
            android.R.attr.textStyle,
            android.R.attr.letterSpacing,
            android.R.attr.lineHeight,
            android.R.attr.textFontWeight,
        ),
    )
    try {
        val sizePx = appearance.getDimensionPixelSize(0, defaultSizePx)
        val fontSize = with(density) { sizePx.toSp() }
        val colors = appearance.getColorStateList(1)
        val color = colors?.defaultColor?.let(::Color)
            ?: resolveColor(android.R.attr.textColorPrimary)
        val familyName = appearance.getString(2) ?: "sans-serif"
        val typefaceStyle = appearance.getInt(3, Typeface.NORMAL)
        val letterSpacingEm = appearance.getFloat(4, 0f)
        val lineHeightPx = if (appearance.hasValue(5)) {
            appearance.getDimensionPixelSize(5, 0)
        } else {
            0
        }
        val weightValue = if (appearance.hasValue(6)) appearance.getInt(6, 400) else 400
        val weight = when {
            bold || weightValue >= 700 -> FontWeight.Bold
            weightValue >= 600 -> FontWeight.SemiBold
            weightValue >= 500 -> FontWeight.Medium
            else -> FontWeight.Normal
        }
        val effectiveStyle = if (bold) Typeface.BOLD else typefaceStyle
        return TextStyle(
            color = color,
            fontSize = fontSize,
            fontFamily = FontFamily(Typeface.create(familyName, effectiveStyle)),
            fontWeight = weight,
            letterSpacing = if (letterSpacingEm == 0f) {
                TextUnit.Unspecified
            } else {
                fontSize * letterSpacingEm
            },
            lineHeight = if (lineHeightPx > 0) {
                with(density) { lineHeightPx.toSp() }
            } else {
                TextUnit.Unspecified
            },
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        )
    } finally {
        appearance.recycle()
    }
}

private fun Context.resolveColor(@AttrRes attr: Int): Color =
    resolveStateColor(attr, enabled = true)

/**
 * Resolves the colour a text appearance uses while the view is disabled.
 * TextView picks this from the appearance's ColorStateList, so reading the
 * same state keeps the disabled rows identical to the XML version.
 */
private fun Context.resolveTextAppearanceDisabledColor(@AttrRes attr: Int): Color {
    val styleAttributes = obtainStyledAttributes(intArrayOf(attr))
    val styleRes = try {
        styleAttributes.getResourceId(0, 0)
    } finally {
        styleAttributes.recycle()
    }
    if (styleRes == 0) {
        return resolveStateColor(android.R.attr.textColorPrimary, enabled = false)
    }
    val appearance = obtainStyledAttributes(
        styleRes,
        intArrayOf(android.R.attr.textColor),
    )
    try {
        val colors = appearance.getColorStateList(0)
            ?: return resolveStateColor(android.R.attr.textColorPrimary, enabled = false)
        return Color(
            colors.getColorForState(
                intArrayOf(-android.R.attr.state_enabled),
                colors.defaultColor,
            ),
        )
    } finally {
        appearance.recycle()
    }
}

private fun Context.resolveStateColor(@AttrRes attr: Int, enabled: Boolean): Color {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return Color.Black
    if (value.resourceId == 0) return Color(value.data)
    val colors = resources.getColorStateList(value.resourceId, theme)
    val state = if (enabled) {
        intArrayOf(android.R.attr.state_enabled)
    } else {
        intArrayOf(-android.R.attr.state_enabled)
    }
    return Color(colors.getColorForState(state, colors.defaultColor))
}

private fun Context.resolveDimension(@AttrRes attr: Int): Float {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return 0f
    return TypedValue.complexToDimension(value.data, resources.displayMetrics)
}

/** Resolves a drawable attribute from the current theme, e.g. the list divider. */
fun Context.themeDrawable(@AttrRes attr: Int): Drawable? {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return null
    return when {
        value.resourceId != 0 -> resources.getDrawable(value.resourceId, theme)
        value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
            ColorDrawable(value.data)
        else -> null
    }
}
