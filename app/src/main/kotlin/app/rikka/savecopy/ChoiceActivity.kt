package app.rikka.savecopy

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Chooser shown for "open with" / "share" requests. Links can only be
 * downloaded, files can either be copied or downloaded.
 *
 * <p>The chooser itself stays a platform [AlertDialog] so the dialog panel,
 * width and dimming are unchanged; only its content view is Compose.</p>
 */
class ChoiceActivity : ComponentActivity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isLink = IntentDataUtils.findHttpUrl(intent) != null
        val content = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ChoiceActivity)
            setViewTreeSavedStateRegistryOwner(this@ChoiceActivity)
            setViewTreeViewModelStoreOwner(this@ChoiceActivity)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                ChoiceScreen(
                    saveEnabled = !isLink,
                    onSaveClick = { startChosenActivity(SaveActivity::class.java) },
                    onDownloadClick = { startChosenActivity(DownloadActivity::class.java) },
                )
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert)
            .setView(content)
            .create()
        // Compose resolves its window recomposer from the dialog's content root,
        // which is not part of the activity view tree, so the owners must be on
        // the dialog decor as well.
        dialog.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
        }
        dialog.setOnDismissListener { finish() }
        dialog.show()
        this.dialog = dialog

        onBackPressedDispatcher.addCallback(this) {
            if (dialog.isShowing) {
                dialog.dismiss()
            } else {
                finish()
            }
        }
    }

    private fun startChosenActivity(activityClass: Class<out ComponentActivity>) {
        if (dialog?.isShowing == true) dialog?.dismiss()

        val source = intent
        val target = Intent(this, activityClass)
        target.action = source.action

        val uris = IntentDataUtils.collectUris(source, false)
        if (uris.isNotEmpty()) {
            val type = source.type ?: "*/*"
            if (uris.size == 1) {
                target.setDataAndType(uris[0], type)
            }
            target.clipData = buildClipData(uris, type)
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            target.setDataAndType(source.data, source.type)
        }

        if (source.hasExtra(Intent.EXTRA_TEXT)) {
            target.putExtra(Intent.EXTRA_TEXT, source.getStringExtra(Intent.EXTRA_TEXT))
        }

        startActivity(target)
        finish()
    }

    private fun buildClipData(uris: List<Uri>, type: String): ClipData {
        if (uris.size == 1) {
            return ClipData.newRawUri(null, uris[0])
        }
        val clipData = ClipData(null, arrayOf(type), ClipData.Item(uris[0]))
        for (i in 1 until uris.size) {
            clipData.addItem(ClipData.Item(uris[i]))
        }
        return clipData
    }
}
