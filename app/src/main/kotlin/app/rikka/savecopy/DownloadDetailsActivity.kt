package app.rikka.savecopy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** A dialog-style, read-only view of the foreground download service. */
class DownloadDetailsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.download_details_title)
        val session = intent?.getIntExtra(DownloadService.EXTRA_SESSION, -1) ?: -1
        setContent {
            DownloadDetailsScreen(session = session, onClose = ::finish)
        }
    }
}
