package com.montauk.voicecapture.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Presence check only -- a connected Bluetooth input device shows up here
 * even though [com.montauk.voicecapture.audio.AudioEngine] doesn't yet route
 * to it (`preferredInputDeviceId` is an unwired hook; see its KDoc). Drives
 * the recording screen's Bluetooth chip so the mockup's chip row has real
 * data to show, without pretending routing is implemented.
 */
fun hasBluetoothInputDevice(context: Context): Boolean {
    val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
    return runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)
}
