package com.example.indoorar.shared.pathfinding

import com.example.indoorar.shared.models.NavEdge
import com.example.indoorar.shared.models.NavGraph
import com.example.indoorar.shared.models.NavNode
import com.example.indoorar.shared.models.Vector3

class AStarPathfinder(private val graph: NavGraph) {

    private val adjacencyList: Map<String, List<NavEdge>> = buildAdjacencyList()
    private val nodesById: Map<String, NavNode> = graph.nodes.associateBy { it.id }

    private fun buildAdjacencyList(): Map<String, List<NavEdge>> {
        val map = mutableMapOf<String, MutableList<NavEdge>>()
        for (node in graph.nodes) {
            map[node.id] = mutableListOf()
        }
        for (edge in graph.edges) {
            map[edge.nodeAId]?.add(edge)
            // Assuming undirected graph
            map[edge.nodeBId]?.add(NavEdge(edge.nodeBId, edge.nodeAId, edge.weight))
        }
        return map
    }

    /**
     * Finds the shortest path between start and end vectors.
     * Snaps the vectors to the nearest nodes in the graph first.
     */
    fun findPath(startPos: Vector3, endPos: Vector3): List<Vector3> {
        if (graph.nodes.isEmpty()) return listOf(startPos, endPos)

        val startNode = findNearestNode(startPos)
        val endNode = findNearestNode(endPos)

        if (startNode == null || endNode == null) return listOf(startPos, endPos)

        val nodePath = findPathBetweenNodes(startNode.id, endNode.id)
        
        // Convert node path to vector path
        val vectorPath = mutableListOf<Vector3>()
        vectorPath.add(startPos)
        vectorPath.addAll(nodePath.map { it.position })
        vectorPath.add(endPos)
        
        return vectorPath
    }

    private fun findNearestNode(pos: Vector3): NavNode? {
        return graph.nodes.minByOrNull { it.position.distanceTo(pos) }
    }

    private fun findPathBetweenNodes(startId: String, endId: String): List<NavNode> {
        if (startId == endId) return listOf(nodesById[startId]!!)

        val openSet = mutableSetOf<String>(startId)
        val cameFrom = mutableMapOf<String, String>()
        
        val gScore = mutableMapOf<String, Float>().withDefault { Float.POSITIVE_INFINITY }
        gScore[startId] = 0f
        
        val fScore = mutableMapOf<String, Float>().withDefault { Float.POSITIVE_INFINITY }
        fScore[startId] = heuristicCostEstimate(startId, endId)

        while (openSet.isNotEmpty()) {
            val currentId = openSet.minByOrNull { fScore.getValue(it) } ?: break
            
            if (currentId == endId) {
                return reconstructPath(cameFrom, currentId)
            }

            openSet.remove(currentId)

            val neighbors = adjacencyList[currentId] ?: emptyList()
            for (edge in neighbors) {
                val neighborId = edge.nodeBId
                val tentativeGScore = gScore.getValue(currentId) + edge.weight

                if (tentativeGScore < gScore.getValue(neighborId)) {
                    cameFrom[neighborId] = currentId
                    gScore[neighborId] = tentativeGScore
                    fScore[neighborId] = tentativeGScore + heuristicCostEstimate(neighborId, endId)
                    if (neighborId !in openSet) {
                        openSet.add(neighborId)
                    }
                }
            }
        }

        // Return empty path if no path found
        return emptyList()
    }

    private fun heuristicCostEstimate(nodeAId: String, nodeBId: String): Float {
        val nodeA = nodesById[nodeAId] ?: return 0f
        val nodeB = nodesById[nodeBId] ?: return 0f
        return nodeA.position.distanceTo(nodeB.position)
    }

    private fun reconstructPath(cameFrom: Map<String, String>, current: String): List<NavNode> {
        var curr = current
        val totalPath = mutableListOf<NavNode>(nodesById[curr]!!)
        while (cameFrom.containsKey(curr)) {
            curr = cameFrom[curr]!!
            totalPath.add(0, nodesById[curr]!!)
        }
        return totalPath
    }
}
