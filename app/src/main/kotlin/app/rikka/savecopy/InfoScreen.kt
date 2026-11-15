package app.rikka.savecopy

import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose replacement for {@code res/layout/activity_info.xml}: the 14sp
 * secondary-colour introduction followed by the 56dp "Settings" row.
 */
@Composable
fun InfoScreen(onOpenSettings: () -> Unit) {
    val theme = rememberPlatformTheme()
    val indication = ripple(color = theme.colorControlHighlight)
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        IntroText(
            text = stringResource(
                R.string.introduction,
                stringResource(R.string.save_a_copy),
            ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = indication,
                    onClick = onOpenSettings,
                )
                .padding(
                    horizontal = theme.dialogPreferredPadding,
                    vertical = 16.dp,
                ),
            contentAlignment = Alignment.TopStart,
        ) {
            BasicText(
                text = stringResource(R.string.settings),
                style = theme.defaultTextStyle.copy(
                    fontSize = 16.sp,
                    color = theme.textColorPrimary,
                ),
            )
        }
    }
}

/**
 * The introduction is the only XML text that used
 * {@code android:lineSpacingExtra="1sp"}. TextView grows each line by the
 * fallback font's own metrics as well, so hosting the real TextView keeps the
 * paragraph breaks, line positions and resulting dialog height identical.
 */
@Composable
private fun IntroText(text: String) {
    val theme = rememberPlatformTheme()
    val density = LocalDensity.current
    val extraSpacing = with(density) { 1.sp.toPx() }
    val textColor = theme.textColorSecondary.toArgb()

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                includeFontPadding = true
                setLineSpacing(extraSpacing, 1f)
                setTextColor(textColor)
            }
        },
        update = { view ->
            if (view.text?.toString() != text) view.text = text
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = theme.dialogPreferredPadding)
            .padding(top = 16.dp, bottom = 8.dp),
    )
}
