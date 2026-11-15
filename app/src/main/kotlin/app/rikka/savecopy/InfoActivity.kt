package app.rikka.savecopy

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class InfoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InfoScreen(
                onOpenSettings = {
                    startActivity(
                        Intent("android.intent.action.APPLICATION_PREFERENCES").setComponent(
                            ComponentName.createRelative(
                                packageName,
                                SettingsActivity::class.java.name,
                            ),
                        ),
                    )
                },
            )
        }
    }
}
