package com.example.indoorar.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.indoorar.domain.Pose
import com.example.indoorar.shared.models.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RealARSessionManager(context: Context) : ARSessionManager, SensorEventListener {
    private val _currentPose = MutableStateFlow<Pose?>(null)
    override val currentPose: StateFlow<Pose?> = _currentPose.asStateFlow()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var currentX = 0f
    private var currentY = 0f
    private var currentZ = 0f
    
    // Default step length in meters
    private val STEP_LENGTH = 0.7f

    override suspend fun startSession() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun stopSession() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                
                val orientationAngles = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                
                // azimuth (yaw), pitch, roll in degrees
                val yaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                
                val currentPose = _currentPose.value ?: Pose(Vector3(currentX, currentY, currentZ), Vector3(0f, 0f, 0f))
                
                _currentPose.value = Pose(
                    position = currentPose.position,
                    rotation = Vector3(pitch, yaw, roll)
                )
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // When a step is detected, move forward in the direction of current yaw
                val yawRad = Math.toRadians(_currentPose.value?.rotation?.y?.toDouble() ?: 0.0)
                
                // Calculate simple translation (assuming z is forward/backward and x is left/right)
                // Assuming yaw 0 is North (Z), yaw 90 is East (X)
                currentZ -= (Math.cos(yawRad) * STEP_LENGTH).toFloat()
                currentX += (Math.sin(yawRad) * STEP_LENGTH).toFloat()
                
                val currentPose = _currentPose.value ?: Pose(Vector3(0f, 0f, 0f), Vector3(0f, 0f, 0f))
                _currentPose.value = Pose(
                    position = Vector3(currentX, currentY, currentZ),
                    rotation = currentPose.rotation
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
