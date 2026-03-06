package com.holonav.app.map

import java.util.PriorityQueue

/**
 * A* Pathfinding Engine for indoor navigation.
 * Operates on the BuildingGraph node/edge structure.
 */
class AStarEngine {

    /**
     * Internal node wrapper for A* open/closed sets.
     */
    private data class AStarNode(
        val nodeId: String,
        val gCost: Float,      // Cost from start to this node
        val fCost: Float,      // gCost + heuristic estimate to goal
        val parent: String?    // Parent node id for path reconstruction
    ) : Comparable<AStarNode> {
        override fun compareTo(other: AStarNode): Int = fCost.compareTo(other.fCost)
    }

    /**
     * Finds the shortest path from [startId] to [goalId] using A*.
     *
     * @return Ordered list of Nodes from start to goal, or empty list if no path exists.
     */
    fun findPath(startId: String, goalId: String): List<Node> {
        val nodes = BuildingGraph.nodes
        val adjacency = BuildingGraph.getAdjacencyList()

        val startNode = nodes[startId] ?: return emptyList()
        val goalNode = nodes[goalId] ?: return emptyList()

        if (startId == goalId) return listOf(startNode)

        // Open set — priority queue sorted by f-cost
        val openSet = PriorityQueue<AStarNode>()
        // Best known g-cost for each node
        val gCosts = mutableMapOf<String, Float>()
        // Closed set
        val closedSet = mutableSetOf<String>()
        // Parent tracking for reconstruction
        val parentMap = mutableMapOf<String, String>()

        // Initialize with start
        val hStart = startNode.distanceTo(goalNode)
        openSet.add(AStarNode(startId, 0f, hStart, null))
        gCosts[startId] = 0f

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()

            // Goal reached — reconstruct path
            if (current.nodeId == goalId) {
                return reconstructPath(parentMap, startId, goalId, nodes)
            }

            if (closedSet.contains(current.nodeId)) continue
            closedSet.add(current.nodeId)

            // Explore neighbors
            val neighbors = adjacency[current.nodeId] ?: continue
            for (edge in neighbors) {
                if (closedSet.contains(edge.to)) continue

                val tentativeG = current.gCost + edge.weight
                val previousG = gCosts[edge.to] ?: Float.MAX_VALUE

                if (tentativeG < previousG) {
                    gCosts[edge.to] = tentativeG
                    parentMap[edge.to] = current.nodeId

                    val neighborNode = nodes[edge.to] ?: continue
                    val h = neighborNode.distanceTo(goalNode)
                    openSet.add(AStarNode(edge.to, tentativeG, tentativeG + h, current.nodeId))
                }
            }
        }

        // No path found
        return emptyList()
    }

    /**
     * Reconstructs the path from parentMap.
     */
    private fun reconstructPath(
        parentMap: Map<String, String>,
        startId: String,
        goalId: String,
        nodes: Map<String, Node>
    ): List<Node> {
        val path = mutableListOf<Node>()
        var currentId: String? = goalId

        while (currentId != null) {
            nodes[currentId]?.let { path.add(0, it) }
            currentId = if (currentId == startId) null else parentMap[currentId]
        }

        return path
    }

    /**
     * Calculates total distance of a path.
     */
    fun pathDistance(path: List<Node>): Float {
        if (path.size < 2) return 0f
        var total = 0f
        for (i in 0 until path.size - 1) {
            total += path[i].distanceTo(path[i + 1])
        }
        return total
    }

    /**
     * Determines the turn direction between three consecutive nodes.
     * Returns angle in degrees: negative = left, positive = right.
     */
    fun turnAngle(prev: Node, current: Node, next: Node): Float {
        val dx1 = current.x - prev.x
        val dy1 = current.y - prev.y
        val dx2 = next.x - current.x
        val dy2 = next.y - current.y

        val cross = dx1 * dy2 - dy1 * dx2
        val dot = dx1 * dx2 + dy1 * dy2

        return Math.toDegrees(Math.atan2(cross.toDouble(), dot.toDouble())).toFloat()
    }
}
