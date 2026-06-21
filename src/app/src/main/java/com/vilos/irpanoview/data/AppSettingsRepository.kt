package com.vilos.irpanoview.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vilos.irpanoview.camera.ThermalDisplaySettings
import com.vilos.irpanoview.camera.hik.HikIrConfigSettings
import com.vilos.irpanoview.camera.hik.HikPreviewSettings
import com.vilos.irpanoview.network.PiConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsRepository(private val context: Context) {

    fun temporalAverageFramesFlow(): Flow<Int> =
        context.appSettingsStore.data.map { prefs ->
            prefs[TEMPORAL_AVERAGE_FRAMES]?.coerceIn(
                HikPreviewSettings.MIN_TEMPORAL_AVERAGE_FRAMES,
                HikPreviewSettings.MAX_TEMPORAL_AVERAGE_FRAMES,
            ) ?: HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES
        }

    suspend fun readTemporalAverageFrames(): Int = temporalAverageFramesFlow().first()

    suspend fun setTemporalAverageFrames(count: Int) {
        val clamped = count.coerceIn(
            HikPreviewSettings.MIN_TEMPORAL_AVERAGE_FRAMES,
            HikPreviewSettings.MAX_TEMPORAL_AVERAGE_FRAMES,
        )
        context.appSettingsStore.edit { prefs ->
            prefs[TEMPORAL_AVERAGE_FRAMES] = clamped
        }
        HikPreviewSettings.setTemporalAverageFrames(clamped)
    }

    suspend fun readDebugStatsEnabled(): Boolean {
        val prefs = context.appSettingsStore.data.first()
        return prefs[DEBUG_STATS_ENABLED] ?: false
    }

    suspend fun setDebugStatsEnabled(enabled: Boolean) {
        context.appSettingsStore.edit { prefs ->
            prefs[DEBUG_STATS_ENABLED] = enabled
        }
    }

    suspend fun readThermalDisplayRange(): Pair<Double, Double> {
        val prefs = context.appSettingsStore.data.first()
        val floorTenths = prefs[THERMAL_FLOOR_TENTHS]
        val ceilingTenths = prefs[THERMAL_CEILING_TENTHS]
        val floor = floorTenths?.let { it / 10.0 } ?: ThermalDisplaySettings.defaultFloorCelsius()
        val ceiling = ceilingTenths?.let { it / 10.0 } ?: ThermalDisplaySettings.defaultCeilingCelsius()
        return ThermalDisplaySettings.clampFloor(floor, ceiling) to
            ThermalDisplaySettings.clampCeiling(ceiling, floor)
    }

    suspend fun setThermalDisplayRange(floorC: Double, ceilingC: Double) {
        val floor = ThermalDisplaySettings.clampFloor(floorC, ceilingC)
        val ceiling = ThermalDisplaySettings.clampCeiling(ceilingC, floor)
        context.appSettingsStore.edit { prefs ->
            prefs[THERMAL_FLOOR_TENTHS] = (floor * 10.0).roundToInt()
            prefs[THERMAL_CEILING_TENTHS] = (ceiling * 10.0).roundToInt()
        }
        ThermalDisplaySettings.setRange(floor, ceiling)
    }

    suspend fun readIrConfig(): Triple<Double, Double, Double> {
        val prefs = context.appSettingsStore.data.first()
        val ems = prefs[EMISSIVITY_HUNDREDTHS]?.let { it / 100.0 }
            ?: HikIrConfigSettings.DEFAULT_EMISSIVITY
        val dist = prefs[DISTANCE_CM]?.let { it / 100.0 }
            ?: HikIrConfigSettings.DEFAULT_DISTANCE_M
        val ambient = prefs[AMBIENT_TENTHS]?.let { it / 10.0 }
            ?: HikIrConfigSettings.DEFAULT_AMBIENT_C
        return Triple(ems, dist, ambient)
    }

    suspend fun setIrConfig(emissivity: Double, distanceM: Double, ambientCelsius: Double) {
        HikIrConfigSettings.set(emissivity, distanceM, ambientCelsius)
        context.appSettingsStore.edit { prefs ->
            prefs[EMISSIVITY_HUNDREDTHS] = (HikIrConfigSettings.emissivity * 100.0).roundToInt()
            prefs[DISTANCE_CM] = (HikIrConfigSettings.distanceM * 100.0).roundToInt()
            prefs[AMBIENT_TENTHS] = (HikIrConfigSettings.ambientCelsius * 10.0).roundToInt()
        }
    }

    fun piHostFlow(): Flow<String> =
        context.appSettingsStore.data.map { prefs ->
            PiConnectionManager.normalizeHost(prefs[PI_HOST])
        }

    suspend fun readPiHost(): String = piHostFlow().first()

    suspend fun setPiHost(host: String) {
        val normalized = PiConnectionManager.normalizeHost(host)
        context.appSettingsStore.edit { prefs ->
            prefs[PI_HOST] = normalized
        }
    }

    companion object {
        private val TEMPORAL_AVERAGE_FRAMES = intPreferencesKey("temporal_average_frames")
        private val THERMAL_FLOOR_TENTHS = intPreferencesKey("thermal_floor_tenths")
        private val THERMAL_CEILING_TENTHS = intPreferencesKey("thermal_ceiling_tenths")
        private val EMISSIVITY_HUNDREDTHS = intPreferencesKey("ir_emissivity_hundredths")
        private val DISTANCE_CM = intPreferencesKey("ir_distance_cm")
        private val AMBIENT_TENTHS = intPreferencesKey("ir_ambient_tenths")
        private val DEBUG_STATS_ENABLED = booleanPreferencesKey("debug_stats_enabled")
        private val PI_HOST = stringPreferencesKey("pi_host")
    }
}
