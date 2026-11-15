package app.rikka.savecopy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

private const val REFRESH_INTERVAL_MS = 250L

/**
 * Compose replacement for {@code res/layout/activity_download.xml}: the same
 * padded status/file/size rows and the end-aligned platform OK button.
 */
@Composable
fun DownloadDetailsScreen(onClose: () -> Unit) {
    val theme = rememberPlatformTheme()
    var status by remember { mutableStateOf(DownloadService.getDownloadStatus()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = DownloadService.getDownloadStatus()
                status = current
                if (current == null || current.state != DownloadService.DownloadState.DOWNLOADING) {
                    break
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        val snapshot = status
        if (snapshot == null) {
            DetailLine(
                text = stringResource(R.string.download_details_status_unavailable),
                style = theme.mediumTextStyle,
                top = 12.dp,
                bottom = 12.dp,
            )
            DetailLine(
                text = stringResource(R.string.download_details_file_name, "—"),
                bottom = 8.dp,
            )
            DetailLine(
                text = stringResource(R.string.download_details_downloaded, "—"),
                bottom = 4.dp,
            )
            DetailLine(
                text = stringResource(R.string.download_details_total, "—"),
                bottom = 4.dp,
            )
            DetailLine(
                text = stringResource(R.string.download_details_average_speed, "—"),
                bottom = 4.dp,
            )
            DetailLine(
                text = stringResource(R.string.download_details_elapsed, "—"),
                bottom = 12.dp,
            )
        } else {
            val fileName = snapshot.fileName?.takeIf { it.isNotEmpty() }
                ?: stringResource(R.string.download_details_file_unknown)
            val elapsedMillis = (System.currentTimeMillis() - snapshot.startedAtMillis).coerceAtLeast(0)
            val averageSpeed = if (elapsedMillis > 0) {
                snapshot.downloadedBytes * 1000.0 / elapsedMillis
            } else {
                0.0
            }
            val statusText = when (snapshot.state) {
                DownloadService.DownloadState.COMPLETED ->
                    stringResource(R.string.download_details_status_completed)
                DownloadService.DownloadState.FAILED -> if (snapshot.message.isNullOrEmpty()) {
                    stringResource(R.string.download_details_status_failed)
                } else {
                    stringResource(
                        R.string.download_details_status_failed_with_reason,
                        snapshot.message,
                    )
                }
                DownloadService.DownloadState.CANCELLED ->
                    stringResource(R.string.download_details_status_cancelled)
                DownloadService.DownloadState.DOWNLOADING ->
                    stringResource(R.string.download_details_status_downloading)
            }
            DetailLine(
                text = statusText,
                style = theme.mediumTextStyle,
                top = 12.dp,
                bottom = 12.dp,
            )
            DetailLine(
                text = stringResource(R.string.download_details_file_name, fileName),
                maxLines = 2,
                bottom = 8.dp,
            )
            DetailLine(
                text = stringResource(
                    R.string.download_details_downloaded,
                    FormatUtils.size(snapshot.downloadedBytes),
                ),
                bottom = 4.dp,
            )
            DetailLine(
                text = stringResource(
                    R.string.download_details_total,
                    if (snapshot.totalBytes > 0) {
                        FormatUtils.size(snapshot.totalBytes)
                    } else {
                        stringResource(R.string.download_details_total_unknown)
                    },
                ),
                bottom = 4.dp,
            )
            DetailLine(
                text = stringResource(
                    R.string.download_details_average_speed,
                    FormatUtils.speed(averageSpeed),
                ),
                bottom = 4.dp,
            )
            DetailLine(
                text = stringResource(
                    R.string.download_details_elapsed,
                    FormatUtils.elapsed(elapsedMillis),
                ),
                bottom = 12.dp,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = theme.dialogPreferredPadding),
            contentAlignment = Alignment.CenterEnd,
        ) {
            PlatformButton(
                text = stringResource(android.R.string.ok),
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun DetailLine(
    text: String,
    style: TextStyle? = null,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    maxLines: Int = Int.MAX_VALUE,
) {
    val theme = rememberPlatformTheme()
    BasicText(
        text = text,
        style = style ?: theme.defaultTextStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = theme.dialogPreferredPadding)
            .padding(top = top, bottom = bottom),
    )
}
