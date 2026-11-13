package cz.medsy.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/** Whether Health Connect can be used on this device at all. */
object HealthConnectAvailability {

    enum class Status { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

    /**
     * Health Connect ships as an APK on Android 13 and below, and is part of the
     * platform from 14. On a device without it every call would throw, so the UI must
     * ask first. Never throws.
     */
    fun status(context: Context): Status = runCatching {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Status.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.UPDATE_REQUIRED
            else -> Status.UNAVAILABLE
        }
    }.getOrDefault(Status.UNAVAILABLE)

    fun isAvailable(context: Context): Boolean = status(context) == Status.AVAILABLE
}
