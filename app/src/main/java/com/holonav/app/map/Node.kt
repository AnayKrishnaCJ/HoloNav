package com.holonav.app.map

import kotlin.math.sqrt

/**
 * Represents a navigable point on the indoor floor plan.
 * Each Node has a unique id, a position in map coordinates, and a human-readable name.
 */
data class Node(
    val id: String,
    val x: Float,
    val y: Float,
    val name: String = "",
    val isDestination: Boolean = false
) {
    /** Euclidean distance to another node */
    fun distanceTo(other: Node): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Represents a walkable connection between two nodes.
 */
data class Edge(
    val from: String, // Node id
    val to: String,   // Node id
    val weight: Float  // Physical distance / cost
)

/**
 * Defines the building's walkable graph.
 * This would normally be loaded from a config file; here we define a sample floor plan.
 */
object BuildingGraph {

    // --- Sample Floor Plan Nodes ---
    // Coordinates are in a 1000x700 virtual map space
    val nodes: Map<String, Node> = listOf(
        // Corridors
        Node("c1", 100f, 350f, "Corridor West"),
        Node("c2", 250f, 350f, "Corridor Junction A"),
        Node("c3", 450f, 350f, "Corridor Center"),
        Node("c4", 650f, 350f, "Corridor Junction B"),
        Node("c5", 850f, 350f, "Corridor East"),

        // North wing
        Node("c6", 250f, 150f, "North Corridor A"),
        Node("c7", 450f, 150f, "North Corridor B"),
        Node("c8", 650f, 150f, "North Corridor C"),

        // South wing
        Node("c9", 250f, 550f, "South Corridor A"),
        Node("c10", 450f, 550f, "South Corridor B"),
        Node("c11", 650f, 550f, "South Corridor C"),

        // Destinations
        Node("entrance", 100f, 500f, "Main Entrance", isDestination = true),
        Node("lobby", 250f, 450f, "Lobby", isDestination = true),
        Node("room_a", 150f, 150f, "Room A (Conference)", isDestination = true),
        Node("room_b", 550f, 100f, "Room B (Lab)", isDestination = true),
        Node("room_c", 850f, 200f, "Room C (Office)", isDestination = true),
        Node("cafeteria", 850f, 500f, "Cafeteria", isDestination = true),
        Node("restroom", 450f, 450f, "Restrooms", isDestination = true),
        Node("elevator", 650f, 250f, "Elevator", isDestination = true),
        Node("stairs", 250f, 250f, "Stairwell", isDestination = true),
        Node("exit", 850f, 650f, "Emergency Exit", isDestination = true)
    ).associateBy { it.id }

    // --- Edges (bidirectional) ---
    val edges: List<Edge> = buildList {
        fun biEdge(a: String, b: String) {
            val na = nodes[a]!!
            val nb = nodes[b]!!
            val w = na.distanceTo(nb)
            add(Edge(a, b, w))
            add(Edge(b, a, w))
        }

        // Main corridor
        biEdge("c1", "c2")
        biEdge("c2", "c3")
        biEdge("c3", "c4")
        biEdge("c4", "c5")

        // North wing
        biEdge("c2", "c6")
        biEdge("c6", "c7")
        biEdge("c7", "c8")
        biEdge("c8", "c4")

        // South wing
        biEdge("c2", "c9")
        biEdge("c9", "c10")
        biEdge("c10", "c11")
        biEdge("c11", "c4")

        // Connect destinations to corridor nodes
        biEdge("entrance", "c1")
        biEdge("entrance", "c9")
        biEdge("lobby", "c2")
        biEdge("lobby", "c9")
        biEdge("room_a", "c6")
        biEdge("stairs", "c6")
        biEdge("stairs", "c2")
        biEdge("room_b", "c7")
        biEdge("elevator", "c8")
        biEdge("elevator", "c4")
        biEdge("room_c", "c5")
        biEdge("restroom", "c3")
        biEdge("restroom", "c10")
        biEdge("cafeteria", "c5")
        biEdge("cafeteria", "c11")
        biEdge("exit", "c11")
        biEdge("exit", "cafeteria")
    }

    /** Build adjacency list for A* */
    fun getAdjacencyList(): Map<String, List<Edge>> {
        return edges.groupBy { it.from }
    }

    /** Get only destination nodes for the picker */
    fun getDestinations(): List<Node> {
        return nodes.values.filter { it.isDestination }.sortedBy { it.name }
    }

    /** Find the nearest graph node to arbitrary map coordinates */
    fun findNearestNode(x: Float, y: Float): Node {
        val queryNode = Node("query", x, y)
        return nodes.values.minByOrNull { it.distanceTo(queryNode) }
            ?: nodes.values.first()
    }
}
