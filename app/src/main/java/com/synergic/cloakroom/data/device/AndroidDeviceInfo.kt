package com.synergic.cloakroom.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.synergic.cloakroom.domain.device.DeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceInfo @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceInfo {
    // ANDROID_ID is exactly what md_user.device_id is meant to hold (see the schema sheet).
    @SuppressLint("HardwareIds")
    override fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
