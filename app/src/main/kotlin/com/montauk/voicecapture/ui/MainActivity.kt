package com.montauk.voicecapture.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.montauk.voicecapture.service.RecordingService
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.session.RecordingMode

class MainActivity : ComponentActivity() {

    /**
     * Carries a debug fixture pick (bead vn-edu.20) across the permission
     * request round-trip: [onRecordTapped] sets this before launching the
     * permission prompt, and the callback below reads it once the grant
     * comes back, since [startRecordingService] can't be called until then.
     */
    private var pendingInjectAsset: String? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startRecordingService(pendingInjectAsset)
        }
        pendingInjectAsset = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startDestination = AppEntryGating.startDestination(
            isRecording = RecordingStateHolder.state.value.isRecording,
        )
        setContent {
            AppNavHost(
                startDestination = startDestination,
                onNewSessionTapped = ::onRecordTapped,
                onStopRecording = ::stopRecordingService,
                onSetMode = ::setRecordingMode,
                onAddTag = ::addRecordingTag,
                onRemoveTag = ::removeRecordingTag,
                onSwapTag = ::swapRecordingTag,
                onApproveTag = ::approveRecordingTag,
            )
        }
    }

    /**
     * [injectAssetFileName] is a bundled debug fixture's filename (e.g.
     * "kitchen-remodel.wav") from the "New Session" tab's long-press picker
     * (debug builds only, see [com.montauk.voicecapture.ui.BottomNavBar]);
     * null for the normal live-mic tap.
     */
    private fun onRecordTapped(injectAssetFileName: String? = null) {
        if (hasRecordAudioPermission()) {
            startRecordingService(injectAssetFileName)
        } else {
            pendingInjectAsset = injectAssetFileName
            requestPermissions.launch(permissionsToRequest())
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun permissionsToRequest(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    private fun startRecordingService(injectAssetFileName: String? = null) {
        // Called only from this direct user tap (or the bottom nav's "New
        // Session" action-tab tap that triggers it), satisfying the Android
        // 14+ rule that a microphone foreground service can't be started
        // from the background.
        val injectAudioSpec = injectAssetFileName?.let { RecordingService.ASSET_PREFIX + it }
        ContextCompat.startForegroundService(this, RecordingService.startIntent(this, injectAudioSpec))
    }

    private fun stopRecordingService() {
        ContextCompat.startForegroundService(this, RecordingService.stopIntent(this))
    }

    private fun setRecordingMode(mode: RecordingMode) {
        ContextCompat.startForegroundService(this, RecordingService.setModeIntent(this, mode))
    }

    /** Bead asn-45m: the recording screen's (+) chip (or the picker's free-form "Add" row) confirmed a new tag. */
    private fun addRecordingTag(tag: String, tagId: String?) {
        ContextCompat.startForegroundService(this, RecordingService.addTagIntent(this, tag, tagId))
    }

    /** Bead asn-45m: the recording screen's ✕ affordance on a chip. */
    private fun removeRecordingTag(tag: String) {
        ContextCompat.startForegroundService(this, RecordingService.removeTagIntent(this, tag))
    }

    /** Bead asn-45m: the recording screen's picker confirmed a replacement for an existing chip. */
    private fun swapRecordingTag(oldTag: String, newTag: String, newTagId: String?) {
        ContextCompat.startForegroundService(this, RecordingService.swapTagIntent(this, oldTag, newTag, newTagId))
    }

    /** Bead asn-0jk: the recording screen's tap-to-approve on a still-unapproved PROPOSED_NEW chip. */
    private fun approveRecordingTag(tag: String) {
        ContextCompat.startForegroundService(this, RecordingService.approveTagIntent(this, tag))
    }
}
