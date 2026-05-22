package com.example.indoorar.ar

import com.example.indoorar.domain.Pose
import com.example.indoorar.shared.models.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.asin

class ARCoreSessionManager : ARSessionManager {
    private val _currentPose = MutableStateFlow<Pose?>(null)
    override val currentPose: StateFlow<Pose?> = _currentPose.asStateFlow()

    override suspend fun startSession() {
        // ARScene handles session start
    }

    override fun stopSession() {
        // ARScene handles session stop
    }

    fun updatePose(tx: Float, ty: Float, tz: Float, qx: Float, qy: Float, qz: Float, qw: Float) {
        // Convert Quaternion to Euler angles (yaw, pitch, roll) in degrees
        // Using standard aviation axes
        val sinr_cosp = 2 * (qw * qx + qy * qz)
        val cosr_cosp = 1 - 2 * (qx * qx + qy * qy)
        val roll = Math.toDegrees(atan2(sinr_cosp, cosr_cosp).toDouble()).toFloat()

        val sinp = Math.sqrt(1.0 + 2.0 * (qw * qy - qx * qz))
        val cosp = Math.sqrt(1.0 - 2.0 * (qw * qy - qx * qz))
        val pitch = Math.toDegrees(2.0 * atan2(sinp, cosp) - Math.PI / 2.0).toFloat()

        val siny_cosp = 2 * (qw * qz + qx * qy)
        val cosy_cosp = 1 - 2 * (qy * qy + qz * qz)
        val yaw = Math.toDegrees(atan2(siny_cosp, cosy_cosp).toDouble()).toFloat()

        _currentPose.value = Pose(
            position = Vector3(tx, ty, tz),
            rotation = Vector3(pitch, -yaw, roll) // ARCore yaw is flipped relative to our convention
        )
    }
}
