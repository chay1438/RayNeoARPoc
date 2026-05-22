package com.example.indoorar.domain

import com.example.indoorar.shared.models.Vector3
import com.example.indoorar.shared.models.Waypoint
import kotlin.math.atan2

data class NavigationState(
    val destination: Waypoint?,
    val distanceMeters: Float,
    val angleDegreesToTurn: Float,
    val isReached: Boolean,
    val pathPoints: List<Vector3> = emptyList()
)

class NavigationEngine {
    companion object {
        const val ARRIVAL_THRESHOLD_METERS = 1.0f
    }

    fun calculateNavigationState(currentPose: Pose, destination: Waypoint?, aStarPath: List<Vector3>? = null): NavigationState {
        if (destination == null) {
            return NavigationState(null, 0f, 0f, false)
        }

        val currentVec = Vector3(currentPose.position.x, currentPose.position.y, currentPose.position.z)
        val distance = currentVec.distanceTo(destination.position)
        
        val dx = destination.position.x - currentPose.position.x
        val dy = destination.position.y - currentPose.position.y
        val dz = destination.position.z - currentPose.position.z
        
        val absoluteAngleRad = atan2(dz.toDouble(), dx.toDouble()).toFloat()
        var absoluteAngleDeg = Math.toDegrees(absoluteAngleRad.toDouble()).toFloat() + 90f
        
        var turnAngleDeg = absoluteAngleDeg - currentPose.rotation.y
        while (turnAngleDeg <= -180f) turnAngleDeg += 360f
        while (turnAngleDeg > 180f) turnAngleDeg -= 360f

        val isReached = distance <= ARRIVAL_THRESHOLD_METERS

        // Generate path points
        val pathPoints = mutableListOf<Vector3>()
        if (!isReached) {
            if (aStarPath != null && aStarPath.size > 1) {
                // Generate dots along the A* path segments
                for (i in 0 until aStarPath.size - 1) {
                    val p1 = aStarPath[i]
                    val p2 = aStarPath[i + 1]
                    val segDist = p1.distanceTo(p2)
                    if (segDist > 0.5f) {
                        val numDots = (segDist / 0.5f).toInt()
                        for (j in 1..numDots) {
                            val fraction = (j * 0.5f) / segDist
                            pathPoints.add(Vector3(
                                p1.x + (p2.x - p1.x) * fraction,
                                p1.y + (p2.y - p1.y) * fraction,
                                p1.z + (p2.z - p1.z) * fraction
                            ))
                        }
                    }
                }
            } else if (distance > 0.5f) {
                // Fallback to straight line
                val numDots = (distance / 0.5f).toInt()
                for (i in 1..numDots) {
                    val fraction = (i * 0.5f) / distance
                    val dotX = currentPose.position.x + dx * fraction
                    val dotZ = currentPose.position.z + dz * fraction
                    val dotY = destination.position.y 
                    pathPoints.add(Vector3(dotX, dotY, dotZ))
                }
            }
        }

        return NavigationState(
            destination = destination,
            distanceMeters = distance,
            angleDegreesToTurn = turnAngleDeg,
            isReached = isReached,
            pathPoints = pathPoints
        )
    }
}
