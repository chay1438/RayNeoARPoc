package com.example.indoorar.shared.models

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun distanceTo(other: Vector3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

@Serializable
data class Waypoint(
    val id: String,
    val name: String,
    val position: Vector3
)

@Serializable
data class NavNode(
    val id: String,
    val position: Vector3
)

@Serializable
data class NavEdge(
    val nodeAId: String,
    val nodeBId: String,
    val weight: Float
)

@Serializable
data class NavGraph(
    val nodes: List<NavNode> = emptyList(),
    val edges: List<NavEdge> = emptyList(),
    val waypoints: List<Waypoint> = emptyList()
)

@Serializable
data class MapLocation(
    val id: String,
    val name: String,
    val cloudAnchorId: String,
    val navGraph: NavGraph
)

@Serializable
data class Product(
    val id: String,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val url: String?,
    val price: Double?,
    val discountedPrice: Double?,
    val discount: Double?
)
