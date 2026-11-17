// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuvio.tv.LeXiaNuvio

/** Where Nuvio's screens ask Hilt for their view model, LeXia's hand-made factory answers. */
@Composable
inline fun <reified VM : ViewModel> hiltViewModel(): VM =
    viewModel(factory = LeXiaNuvio.viewModelFactory(LocalContext.current))
