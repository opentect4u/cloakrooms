package com.synergic.cloakroom.domain.device

/** Identity of this device, stored in md_user.device_id and the *_device_id columns. */
fun interface DeviceInfo {
    /** Android ID. */
    fun deviceId(): String
}
