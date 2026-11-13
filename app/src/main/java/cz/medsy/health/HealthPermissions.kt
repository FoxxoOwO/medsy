package cz.medsy.health

/**
 * Health Connect permissions. Only the vitals the app actually records are requested —
 * asking for steps or sleep would be an unnecessary privacy cost for a medication app.
 */
object HealthPermissions {

    const val WRITE_BLOOD_PRESSURE = "android.permission.health.WRITE_BLOOD_PRESSURE"
    const val WRITE_HEART_RATE = "android.permission.health.WRITE_HEART_RATE"
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val READ_BLOOD_PRESSURE = "android.permission.health.READ_BLOOD_PRESSURE"

    val writePermissions: Set<String> = setOf(
        WRITE_BLOOD_PRESSURE,
        WRITE_HEART_RATE,
    )

    val readPermissions: Set<String> = setOf(
        READ_HEART_RATE,
        READ_BLOOD_PRESSURE,
    )

    val all: Set<String> = writePermissions + readPermissions

    /**
     * True when every permission the writer needs is present. Health Connect returns the set it
     * granted, but an empty set is also what you get when the provider refuses the request
     * outright, so the caller re-reads the stored grants before trusting the result.
     */
    fun allowsWrites(granted: Set<String>): Boolean = writePermissions.all { it in granted }
}
