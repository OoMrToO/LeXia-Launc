// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nuvio.tv.ui.screens.plugin.PluginScreen
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Opens Nuvio's own plugin screen (ui/screens/plugin/PluginScreen.kt) by itself, in Nuvio's theme,
 * the way Nuvio's navigation does; Back closes it. Nothing else of Nuvio's app is shown.
 */
class LeXiaPluginsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NuvioTheme {
                PluginScreen(onBackPress = { finish() })
            }
        }
    }
}
