package app.rikka.savecopy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** A dialog-style, read-only view of the foreground download service. */
class DownloadDetailsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.download_details_title)
        setContent {
            DownloadDetailsScreen(onClose = ::finish)
        }
    }
}
