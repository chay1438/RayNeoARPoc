package com.example.indoorar.data

import android.content.Context
import com.example.indoorar.shared.models.Waypoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WaypointRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("indoorar_waypoints", Context.MODE_PRIVATE)
    private val WAYPOINTS_KEY = "waypoints_list"

    fun saveWaypoints(waypoints: List<Waypoint>) {
        val jsonString = Json.encodeToString(waypoints)
        prefs.edit().putString(WAYPOINTS_KEY, jsonString).apply()
    }

    fun loadWaypoints(): List<Waypoint> {
        val jsonString = prefs.getString(WAYPOINTS_KEY, null)
        if (jsonString.isNullOrEmpty()) {
            return emptyList()
        }
        return try {
            Json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
