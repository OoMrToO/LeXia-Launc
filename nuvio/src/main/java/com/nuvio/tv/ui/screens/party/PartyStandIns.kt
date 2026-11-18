// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (watch parties). Not Nuvio code: keep it minimal. A party is never active, so these never show.
package com.nuvio.tv.ui.screens.party

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyError
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyMember
import com.nuvio.tv.core.party.PartyProfile
import com.nuvio.tv.core.party.PartyUiState

@Composable
fun PartyPanel(
    state: PartyUiState,
    profile: PartyProfile,
    playerMedia: PartyMedia,
    isSupported: Boolean,
    canStart: Boolean,
    canShareStream: Boolean,
    onStart: () -> Unit,
    onSwitchParty: () -> Unit,
    onWatchWithParty: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onRemove: (PartyMember) -> Unit,
    onHostOnlyChange: (Boolean) -> Unit,
    onSharingChange: (Boolean) -> Unit,
    behindMs: Long?,
    onCatchUp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) = Unit

@Composable
fun PartyStatusPill(
    visible: Boolean,
    members: List<PartyMember>,
    message: String?,
    modifier: Modifier = Modifier,
) = Unit

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = Unit

internal val PartyError.messageRes: Int
    get() = R.string.party_error_not_found
