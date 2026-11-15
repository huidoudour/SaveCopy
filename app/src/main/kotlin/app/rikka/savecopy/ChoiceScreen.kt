package app.rikka.savecopy

import androidx.compose.foundation.Indication
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Compose replacement for {@code res/layout/activity_choice.xml}: a vertical
 * list with 8dp top/bottom padding and two 48dp-tall list items.
 */
@Composable
fun ChoiceScreen(
    saveEnabled: Boolean,
    onSaveClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val theme = rememberPlatformTheme()
    val indication = ripple(color = theme.colorControlHighlight)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        ChoiceRow(
            text = stringResource(R.string.save_file_copy),
            enabled = saveEnabled,
            indication = indication,
            onClick = onSaveClick,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceRow(
            text = stringResource(R.string.download_file_copy),
            enabled = true,
            indication = indication,
            onClick = onDownloadClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChoiceRow(
    text: String,
    enabled: Boolean,
    indication: Indication,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = rememberPlatformTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = if (enabled) {
        theme.listItemTextStyle
    } else {
        theme.listItemTextStyle.copy(color = theme.listItemTextColorDisabled)
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(text = text, style = textStyle)
    }
}
