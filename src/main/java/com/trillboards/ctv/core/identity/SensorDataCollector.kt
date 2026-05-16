package com.trillboards.ctv.core.identity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Collects environmental sensor readings: ambient light (lux) and barometric pressure (hPa).
 *
 * These sensors help infer venue characteristics:
 * - Ambient light: indoor vs outdoor, daytime vs nighttime, bright retail vs dim bar
 * - Barometric pressure: floor-level estimation, weather context
 *
 * No additional permissions required — hardware sensors are freely readable.
 * Returns null for sensors that are not present on the device.
 */
data class SensorSnapshot(
    val ambientLightLux: Float?,
    val barometerPressureHpa: Float?,
    val sensorTimestampMs: Long
)

object SensorDataCollector {

    private const val TAG = "SensorDataCollector"
    private const val READING_TIMEOUT_MS = 2000L

    /**
     * Collect a single reading from ambient light and barometer sensors.
     *
     * @param context Application context
     * @return [SensorSnapshot] with available readings, or null on complete failure
     */
    suspend fun collect(context: Context): SensorSnapshot? = withContext(Dispatchers.IO) {
        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return@withContext null

            val lightReading = AtomicReference<Float?>(null)
            val pressureReading = AtomicReference<Float?>(null)

            val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

            if (lightSensor == null && pressureSensor == null) {
                Log.d(TAG, "No light or pressure sensors available")
                return@withContext null
            }

            val lightListener = if (lightSensor != null) {
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            if (it.values.isNotEmpty()) {
                                lightReading.set(it.values[0])
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
            } else null

            val pressureListener = if (pressureSensor != null) {
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            if (it.values.isNotEmpty()) {
                                pressureReading.set(it.values[0])
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
            } else null

            // Register listeners — deliver callbacks on the main Looper so they actually fire.
            // SensorManager.registerListener() requires a Looper for event delivery;
            // Dispatchers.IO threads have no Looper, so we provide the main one explicitly.
            val sensorHandler = Handler(Looper.getMainLooper())
            lightListener?.let {
                sensorManager.registerListener(it, lightSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            }
            pressureListener?.let {
                sensorManager.registerListener(it, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
            }

            // Wait for readings
            delay(READING_TIMEOUT_MS)

            // Unregister listeners
            lightListener?.let { sensorManager.unregisterListener(it) }
            pressureListener?.let { sensorManager.unregisterListener(it) }

            val snapshot = SensorSnapshot(
                ambientLightLux = lightReading.get(),
                barometerPressureHpa = pressureReading.get(),
                sensorTimestampMs = System.currentTimeMillis()
            )

            Log.d(TAG, "Sensor data: light=${snapshot.ambientLightLux}lux, pressure=${snapshot.barometerPressureHpa}hPa")
            snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Sensor data collection failed: ${e.message}")
            null
        }
    }
}
