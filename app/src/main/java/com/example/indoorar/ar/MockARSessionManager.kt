package com.example.indoorar.ar

import com.example.indoorar.domain.Pose
import com.example.indoorar.shared.models.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * A mock implementation of the RayNeo ARDK Session Manager.
 * Simulates a user walking around in a 3D space by updating coordinates periodically.
 */
class MockARSessionManager : ARSessionManager {

    private val _currentPose = MutableStateFlow<Pose?>(null)
    override val currentPose: StateFlow<Pose?> = _currentPose.asStateFlow()

    private var simulationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override suspend fun startSession() {
        // In a real implementation, this would wait for Camera permissions
        // and initialize the RayNeo ARDK or Mercury SDK.
        
        // Start simulating movement
        startSimulation()
    }

    override fun stopSession() {
        simulationJob?.cancel()
        simulationJob = null
        _currentPose.value = null
    }

    private fun startSimulation() {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            var time = 0f
            while (true) {
                // Simulate walking in a circle of radius 2 meters
                val x = 2f * cos(time)
                val z = 2f * sin(time)
                val y = 1.6f // Head height approx 1.6m
                
                _currentPose.value = Pose(
                    position = Vector3(x, y, z),
                    rotation = Vector3(0f, time * 10f, 0f) // Looking around
                )
                time += 0.1f
                delay(100) // Update 10 times a second
            }
        }
    }
}
