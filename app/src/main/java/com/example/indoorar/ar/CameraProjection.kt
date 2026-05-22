package com.example.indoorar.ar

import com.example.indoorar.domain.Pose
import com.example.indoorar.shared.models.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object CameraProjection {
    private const val FOV_Y_DEGREES = 60f
    private const val NEAR_PLANE = 0.1f
    
    data class ScreenPoint(val x: Float, val y: Float, val isVisible: Boolean, val depth: Float)

    /**
     * Projects a 3D world coordinate to a 2D screen coordinate.
     * Assumes screen coordinates are [0, 1] for width and height.
     */
    fun projectToScreen(worldPoint: Vector3, cameraPose: Pose, screenWidth: Float, screenHeight: Float): ScreenPoint {
        // 1. Translate point relative to camera
        val dx = worldPoint.x - cameraPose.position.x
        val dy = worldPoint.y - cameraPose.position.y
        val dz = worldPoint.z - cameraPose.position.z

        // 2. Rotate around Y axis (Yaw)
        // Note: For simplicity, we only handle Yaw (Y-axis rotation) for now
        val yawRad = Math.toRadians(cameraPose.rotation.y.toDouble()).toFloat()
        val cosY = cos(-yawRad)
        val sinY = sin(-yawRad)

        // Standard 3D rotation matrix for Y axis.
        // ARCore uses -Z as forward. We negate rz so that positive rz means "in front of camera".
        val rx = dx * cosY - dz * sinY
        val rz = -(dx * sinY + dz * cosY)
        val ry = dy // No pitch/roll in this simple POC

        // If the point is behind the camera, it's not visible
        if (rz < NEAR_PLANE) {
            return ScreenPoint(-1f, -1f, false, rz)
        }

        // 3. Perspective Projection
        val fovRad = Math.toRadians(FOV_Y_DEGREES.toDouble()).toFloat()
        val focalLength = 1f / tan(fovRad / 2f)
        val aspectRatio = screenWidth / screenHeight

        val projectedX = (rx / rz) * focalLength / aspectRatio
        val projectedY = (ry / rz) * focalLength

        // Map from [-1, 1] NDC to [0, width/height] screen coordinates
        val screenX = (projectedX + 1f) * 0.5f * screenWidth
        val screenY = (1f - (projectedY + 1f) * 0.5f) * screenHeight // Invert Y for screen coords

        // Check if within bounds
        val isVisible = screenX in 0f..screenWidth && screenY in 0f..screenHeight

        return ScreenPoint(screenX, screenY, isVisible, rz)
    }
}
