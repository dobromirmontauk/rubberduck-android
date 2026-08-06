package com.montauk.voicecapture.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Presence check only, used by [RecordingScreen] as the pre-routing fallback
 * for the brief window before [com.montauk.voicecapture.audio.MicAudioSource]'s
 * first real [com.montauk.voicecapture.audio.AudioRouteSelector] decision
 * lands (bead vn-edu.2 wired up the actual routing; this just answers "is
 * there a Bluetooth mic at all" for the chip to show something sensible
 * before that).
 */
fun hasBluetoothInputDevice(context: Context): Boolean {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
    return runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }.getOrDefault(false)
}
