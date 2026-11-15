package app.rikka.savecopy

import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.Switch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The XML settings screen used a platform [Switch]. Compose's switch follows
 * Material 3 and looks different from the DeviceDefault/MIUI switch the app
 * shipped with, so the real platform widget is hosted through [AndroidView];
 * its text, padding, ripple foreground and disabled styling stay byte-for-byte
 * the same as the XML version.
 */
@Composable
fun PlatformSwitchRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val theme = rememberPlatformTheme()
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val minHeightPx = with(density) { 56.dp.roundToPx() }
    val paddingPx = with(density) { 16.dp.roundToPx() }
    val horizontalPaddingPx = with(density) { theme.dialogPreferredPadding.roundToPx() }

    AndroidView(
        factory = { ctx ->
            Switch(ctx).apply {
                background = null
                foreground = ctx.themeDrawable(android.R.attr.selectableItemBackground)
                minimumHeight = minHeightPx
                setPaddingRelative(
                    horizontalPaddingPx,
                    paddingPx,
                    horizontalPaddingPx,
                    paddingPx,
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setOnCheckedChangeListener { _, isChecked -> currentOnCheckedChange(isChecked) }
            }
        },
        update = { view ->
            if (view.text?.toString() != text) view.text = text
            if (view.isChecked != checked || view.isEnabled != enabled) {
                // Do not report the programmatic state sync back as a user toggle.
                view.setOnCheckedChangeListener(null)
                view.isChecked = checked
                view.isEnabled = enabled
                view.setOnCheckedChangeListener { _, isChecked ->
                    currentOnCheckedChange(isChecked)
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The download details screen used a platform [Button]. Hosting the real
 * widget keeps the DeviceDefault/MIUI button background, elevation, minimum
 * size and text appearance identical to the XML version.
 */
@Composable
fun PlatformButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    AndroidView(
        factory = { ctx ->
            Button(ctx).apply {
                this.text = text
                setOnClickListener { currentOnClick() }
            }
        },
        update = { view -> if (view.text?.toString() != text) view.text = text },
        modifier = modifier,
    )
}

/** The 1dp {@code ?android:attr/listDivider} used by the settings screen. */
@Composable
fun PlatformDivider(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            View(ctx).apply {
                background = ctx.themeDrawable(android.R.attr.listDivider)
            }
        },
        modifier = modifier.height(1.dp),
    )
}
