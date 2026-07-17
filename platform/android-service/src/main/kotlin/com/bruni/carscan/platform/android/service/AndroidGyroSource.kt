package com.bruni.carscan.platform.android.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.bruni.carscan.core.data.GyroSample
import com.bruni.carscan.core.data.GyroSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The device gyroscope, reported as yaw rate about the *vehicle's* vertical axis.
 *
 * A phone mounted on a vent or windscreen sits at an arbitrary angle, so the raw
 * [Sensor.TYPE_GYROSCOPE] axes are not the car's. We take the world→device rotation from
 * [Sensor.TYPE_ROTATION_VECTOR] and project the raw angular-velocity vector onto the world "up"
 * axis (gravity): for a car on level ground that is the vehicle's vertical axis, so the projection
 * is the yaw rate a cornering car actually feels, independent of how the phone is angled.
 *
 * `up` expressed in device coordinates is the third row of the rotation matrix, so the projection
 * is `R[6]*gx + R[7]*gy + R[8]*gz`.
 *
 * A device with no gyroscope simply produces no fixes — the flow closes at once, and
 * HarshEventDetector falls back to GPS-bearing cornering on its own.
 */
class AndroidGyroSource(context: Context) : GyroSource {

    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override val yawRate: Flow<GyroSample> = callbackFlow {
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (gyro == null) {
            close()
            return@callbackFlow
        }

        // Latest world→device rotation matrix; identity until the first rotation-vector event, so
        // before any tilt is known the projection is just the device's own z axis.
        val r = FloatArray(9) { if (it % 4 == 0) 1f else 0f }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR ->
                        SensorManager.getRotationMatrixFromVector(r, event.values)
                    Sensor.TYPE_GYROSCOPE -> {
                        val yaw = r[6] * event.values[0] + r[7] * event.values[1] + r[8] * event.values[2]
                        trySend(GyroSample(tsMs = event.timestamp / 1_000_000L, yawRateRadPerSec = yaw))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        if (rotation != null) sensors.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)

        awaitClose { sensors.unregisterListener(listener) }
    }
}
