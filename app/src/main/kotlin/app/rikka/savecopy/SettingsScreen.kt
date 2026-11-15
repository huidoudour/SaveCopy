package app.rikka.savecopy

import android.net.Uri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose replacement for {@code res/layout/activity_settings.xml}. The two
 * platform switches stay real platform widgets (see [PlatformSwitchRow]) so
 * their DeviceDefault/MIUI rendering is unchanged.
 */
@Composable
fun SettingsScreen(
    preferAppFolder: Boolean,
    useCustomFolder: Boolean,
    customFolderPath: String?,
    onPreferAppFolderChange: (Boolean) -> Unit,
    onUseCustomFolderChange: (Boolean) -> Unit,
    onPickCustomFolder: () -> Unit,
) {
    val theme = rememberPlatformTheme()
    val indication = ripple(color = theme.colorControlHighlight)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        PlatformSwitchRow(
            text = stringResource(R.string.settings_prefer_app_folder),
            checked = preferAppFolder,
            enabled = !useCustomFolder,
            onCheckedChange = onPreferAppFolderChange,
        )
        SummaryText(
            text = stringResource(R.string.settings_prefer_app_folder_summary),
            enabled = !useCustomFolder,
            topPadding = 8.dp,
            bottomPadding = 16.dp,
        )

        PlatformDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = theme.dialogPreferredPadding),
        )

        PlatformSwitchRow(
            text = stringResource(R.string.settings_use_custom_folder),
            checked = useCustomFolder,
            enabled = !preferAppFolder,
            onCheckedChange = onUseCustomFolderChange,
        )
        SummaryText(
            text = stringResource(R.string.settings_use_custom_folder_summary),
            enabled = true,
            topPadding = 8.dp,
            bottomPadding = 8.dp,
        )

        if (useCustomFolder) {
            CustomFolderRow(
                path = customFolderPath,
                indication = indication,
                onClick = onPickCustomFolder,
            )
        }
    }
}

@Composable
private fun SummaryText(
    text: String,
    enabled: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    val theme = rememberPlatformTheme()
    BasicText(
        text = text,
        style = theme.defaultTextStyle.copy(
            color = if (enabled) theme.defaultTextColor else theme.defaultTextColorDisabled,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = theme.dialogPreferredPadding)
            .padding(top = topPadding, bottom = bottomPadding)
            .alpha(if (enabled) 1f else 0.38f),
    )
}

@Composable
private fun CustomFolderRow(
    path: String?,
    indication: Indication,
    onClick: () -> Unit,
) {
    val theme = rememberPlatformTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val displayedPath = remember(path) { displayPath(path) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick,
            )
            .padding(
                horizontal = theme.dialogPreferredPadding,
                vertical = 16.dp,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = stringResource(R.string.settings_custom_folder_path),
                style = theme.defaultTextStyle.copy(fontSize = 16.sp),
            )
            BasicText(
                text = displayedPath
                    ?: stringResource(R.string.settings_custom_folder_not_set),
                style = theme.defaultTextStyle.copy(
                    fontSize = 14.sp,
                    color = theme.textColorSecondary,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun displayPath(path: String?): String? {
    if (path == null) return null
    val segment = Uri.parse(path).lastPathSegment ?: return path
    return segment.replace("tree:", "").replace("primary:", "Internal Storage/")
}
