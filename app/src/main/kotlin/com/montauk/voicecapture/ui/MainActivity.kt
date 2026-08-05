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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            startRecordingService()
        }
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
            )
        }
    }

    private fun onRecordTapped() {
        if (hasRecordAudioPermission()) {
            startRecordingService()
        } else {
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

    private fun startRecordingService() {
        // Called only from this direct user tap (or the bottom nav's "New
        // Session" action-tab tap that triggers it), satisfying the Android
        // 14+ rule that a microphone foreground service can't be started
        // from the background.
        ContextCompat.startForegroundService(this, RecordingService.startIntent(this))
    }

    private fun stopRecordingService() {
        ContextCompat.startForegroundService(this, RecordingService.stopIntent(this))
    }

    private fun setRecordingMode(mode: RecordingMode) {
        ContextCompat.startForegroundService(this, RecordingService.setModeIntent(this, mode))
    }
}
