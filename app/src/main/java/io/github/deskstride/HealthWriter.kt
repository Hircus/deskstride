package io.github.deskstride

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import java.time.Instant
import java.time.ZoneId

/** Writes the session to Health Connect; Samsung Health (or any other app) reads it from there. */
class HealthWriter(private val ctx: Context) {

    companion object {
        /** without these there is no point in sending: the workout and its distance */
        private val REQUIRED = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
        )
        val PERMISSIONS = REQUIRED + setOf(
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
        )
        /**
         * Opens this app's page in Health Connect (permissions and app data).
         * Android 14+: built into system settings. Fallback: Health Connect settings.
         */
        fun openAppPage(ctx: Context) {
            val appPage = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                .putExtra(Intent.EXTRA_PACKAGE_NAME, ctx.packageName)
            val fallback = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            runCatching { ctx.startActivity(appPage) }.recoverCatching { ctx.startActivity(fallback) }
        }

        fun available(ctx: Context) =
            HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
    }

    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

    /** Sends the session and updates its status in the history. Never throws. */
    suspend fun send(store: SessionStore, s: Session): HcStatus {
        val status = runCatching {
            check(available(ctx)) { ctx.getString(R.string.hc_unavailable) }
            val granted = client.permissionController.getGrantedPermissions()
            check(granted.containsAll(REQUIRED)) { ctx.getString(R.string.hc_missing_permissions) }
            write(s, granted)
        }.fold({ HcStatus.Sent }, { HcStatus.Failed(it.message ?: it.javaClass.simpleName) })
        store.setHc(s.startMs, status)
        io.github.deskstride.widget.Widgets.updateAll(ctx)
        return status
    }

    suspend fun hasPermissions() = client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /**
     * Writes only the data types that are granted: a permission added in a newer version
     * (e.g. heart rate) doesn't block sending until you grant it.
     */
    suspend fun write(s: Session, granted: Set<String>) {
        val start = Instant.ofEpochMilli(s.startMs)
        val end = Instant.ofEpochMilli(maxOf(s.endMs, s.startMs + 1000))
        val off = ZoneId.systemDefault().rules.getOffset(start)
        // clientRecordId: rewriting the same session updates it in Health Connect instead of duplicating it
        fun meta(kind: String) = Metadata(clientRecordId = "deskstride-${s.startMs}-$kind")

        val records = mutableListOf<Record>(
            ExerciseSessionRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                title = ctx.getString(R.string.hc_session_title), metadata = meta("session"),
            ),
            DistanceRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                distance = Length.meters(s.distanceM.toDouble()), metadata = meta("dist"),
            ),
            SpeedRecord(
                startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
                // samples keep coming after the last movement (endMs): Health Connect rejects the whole insert
                samples = s.speedSamples.filter { it.first in s.startMs..end.toEpochMilli() }.map {
                    SpeedRecord.Sample(Instant.ofEpochMilli(it.first), Velocity.kilometersPerHour(it.second))
                },
                metadata = meta("speed"),
            ),
        )
        // exercise calories measured by the treadmill: "active" ("total" would include resting metabolism)
        if (s.calories > 0) records += ActiveCaloriesBurnedRecord(
            startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
            energy = Energy.kilocalories(s.calories.toDouble()), metadata = meta("kcal"),
        )
        // can be turned off: a phone in the pocket or a watch already counts the same steps
        if (s.steps > 0 && Walk.sendSteps(ctx)) records += StepsRecord(
            startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
            count = s.steps.toLong(), metadata = meta("steps"),
        )
        // same range rule as speed
        val hr = s.hrSamples.filter { it.first in s.startMs..end.toEpochMilli() }
        if (hr.isNotEmpty()) records += HeartRateRecord(
            startTime = start, startZoneOffset = off, endTime = end, endZoneOffset = off,
            samples = hr.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.first), it.second.toLong()) },
            metadata = meta("hr"),
        )
        client.insertRecords(records.filter { HealthPermission.getWritePermission(it::class) in granted })
    }
}
