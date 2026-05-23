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
                // We now draw continuous lines in UI, so just return the actual path nodes.
                // The AStarPath already contains the user position as the first node.
                pathPoints.addAll(aStarPath)
            } else if (distance > 0.5f) {
                // Fallback to straight line to destination
                pathPoints.add(currentVec)
                pathPoints.add(Vector3(destination.position.x, destination.position.y, destination.position.z))
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
