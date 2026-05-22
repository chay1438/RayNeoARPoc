package com.example.indoorar.ar

import com.example.indoorar.domain.Pose
import kotlinx.coroutines.flow.StateFlow

interface ARSessionManager {
    /**
     * Emits the current device pose in the world space.
     * Null if tracking is lost or not started.
     */
    val currentPose: StateFlow<Pose?>

    /**
     * Requests necessary permissions and starts the AR session.
     */
    suspend fun startSession()

    /**
     * Stops the AR session and releases camera resources.
     */
    fun stopSession()
}
