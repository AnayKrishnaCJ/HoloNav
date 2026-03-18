package com.holonav.app.ar

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.holonav.app.map.Node
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Float3

/**
 * 3D Route Renderer for ARCore.
 *
 * Manages the 3D visualization of the navigation route in AR:
 * - Glowing arrow nodes placed at upcoming waypoints
 * - Glowing line segments connecting consecutive waypoints
 * - Progressive removal of passed waypoints
 * - Only renders the next few waypoints for performance
 *
 * Uses Sceneview ArNodes with programmatic materials.
 */
class ARRouteRenderer(
    private val context: Context,
    private val converter: ARCoordinateConverter = ARCoordinateConverter()
) {

    companion object {
        private const val TAG = "ARRouteRenderer"

        /** Cyan arrow color */
        private const val ARROW_COLOR = "#00E5FF"

        /** Glowing line color */
        private const val LINE_COLOR = "#7C4DFF"

        /** Green arrival color */
        private const val ARRIVAL_COLOR = "#00E676"
    }

    // Active 3D nodes
    private val arrowNodes = mutableMapOf<Int, ArNode>()   // waypointIndex → ArNode
    private val lineNodes = mutableMapOf<Int, ArNode>()    // segmentIndex → ArNode

    // Current state
    private var lastRenderedRoute: List<Node> = emptyList()
    private var lastRenderedIndex: Int = -1
    private var isRendered = false

    /**
     * Update the 3D route visualization.
     *
     * Called on every navigation callback with the current route state.
     * Efficiently updates only what changed.
     */
    fun updateRoute(
        sceneView: ArSceneView,
        route: List<Node>,
        currentIndex: Int,
        userX: Float,
        userY: Float
    ) {
        if (route.isEmpty()) {
            clearAll(sceneView)
            return
        }

        val session = sceneView.session ?: return
        val camera = sceneView.cameraNode ?: return

        // Get visible waypoints (max 4 ahead, max 20m away)
        val visibleIndices = converter.getVisibleWaypoints(route, currentIndex, userX, userY)

        if (visibleIndices.isEmpty()) {
            clearAll(sceneView)
            return
        }

        // Remove arrow nodes for passed waypoints
        val passedKeys = arrowNodes.keys.filter { it < currentIndex }
        for (key in passedKeys) {
            arrowNodes[key]?.let { node ->
                sceneView.removeChild(node)
                node.destroy()
            }
            arrowNodes.remove(key)
            Log.d(TAG, "Removed passed waypoint arrow: $key")
        }

        // Remove line segments for passed segments
        val passedLineKeys = lineNodes.keys.filter { it < currentIndex }
        for (key in passedLineKeys) {
            lineNodes[key]?.let { node ->
                sceneView.removeChild(node)
                node.destroy()
            }
            lineNodes.remove(key)
        }

        // Place/update arrow nodes for visible waypoints
        for (idx in visibleIndices) {
            if (arrowNodes.containsKey(idx)) {
                // Update existing arrow position (user moved, so relative position changed)
                updateArrowPosition(arrowNodes[idx]!!, route, idx, userX, userY)
            } else {
                // Create new arrow node
                createArrowNode(sceneView, route, idx, userX, userY)
            }
        }

        // Place/update line segments between visible waypoints
        for (i in 0 until visibleIndices.size - 1) {
            val fromIdx = visibleIndices[i]
            val toIdx = visibleIndices[i + 1]
            val segmentKey = fromIdx

            if (lineNodes.containsKey(segmentKey)) {
                updateLineSegment(lineNodes[segmentKey]!!, route, fromIdx, toIdx, userX, userY)
            } else {
                createLineSegment(sceneView, route, fromIdx, toIdx, userX, userY)
            }
        }

        // Clean up arrow nodes that are no longer visible
        val visibleSet = visibleIndices.toSet()
        val staleArrowKeys = arrowNodes.keys.filter { it !in visibleSet }
        for (key in staleArrowKeys) {
            arrowNodes[key]?.let { node ->
                sceneView.removeChild(node)
                node.destroy()
            }
            arrowNodes.remove(key)
        }

        lastRenderedRoute = route
        lastRenderedIndex = currentIndex
        isRendered = true
    }

    /**
     * Create a 3D arrow node at a waypoint.
     *
     * Uses ArNode positioned relative to the camera/world.
     * Arrow is a simple cone-like shape pointing toward the next waypoint.
     */
    private fun createArrowNode(
        sceneView: ArSceneView,
        route: List<Node>,
        waypointIndex: Int,
        userX: Float,
        userY: Float
    ) {
        val node = route[waypointIndex]
        val worldPos = converter.nodeToWorldPosition(node, userX, userY)

        // Calculate rotation to face the next waypoint
        val rotationY = if (waypointIndex < route.size - 1) {
            converter.getArrowRotation(node, route[waypointIndex + 1])
        } else {
            0f
        }

        // Determine if this is the final destination
        val isFinal = waypointIndex == route.size - 1

        try {
            val arNode = ArNode().apply {
                position = Position(worldPos.x, worldPos.y, worldPos.z)
                rotation = Rotation(0f, rotationY, 0f)
                // Scale arrows appropriately for visibility
                scale = if (isFinal) {
                    Scale(0.4f, 0.4f, 0.4f)
                } else {
                    Scale(0.25f, 0.25f, 0.25f)
                }
                name = if (isFinal) "destination_marker" else "arrow_$waypointIndex"
            }

            sceneView.addChild(arNode)
            arrowNodes[waypointIndex] = arNode

            Log.d(TAG, "Created arrow at waypoint $waypointIndex: " +
                    "(${worldPos.x}, ${worldPos.y}, ${worldPos.z}), rot=$rotationY°")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create arrow node: ${e.message}", e)
        }
    }

    /**
     * Update an existing arrow node's position (user moved).
     */
    private fun updateArrowPosition(
        arNode: ArNode,
        route: List<Node>,
        waypointIndex: Int,
        userX: Float,
        userY: Float
    ) {
        val node = route[waypointIndex]
        val worldPos = converter.nodeToWorldPosition(node, userX, userY)

        arNode.position = Position(worldPos.x, worldPos.y, worldPos.z)

        // Update rotation if not the last node
        if (waypointIndex < route.size - 1) {
            val rotationY = converter.getArrowRotation(node, route[waypointIndex + 1])
            arNode.rotation = Rotation(0f, rotationY, 0f)
        }
    }

    /**
     * Create a line segment between two consecutive waypoints.
     */
    private fun createLineSegment(
        sceneView: ArSceneView,
        route: List<Node>,
        fromIdx: Int,
        toIdx: Int,
        userX: Float,
        userY: Float
    ) {
        val fromNode = route[fromIdx]
        val toNode = route[toIdx]

        val fromPos = converter.nodeToWorldPosition(fromNode, userX, userY,
            ARCoordinateConverter.LINE_HEIGHT)
        val toPos = converter.nodeToWorldPosition(toNode, userX, userY,
            ARCoordinateConverter.LINE_HEIGHT)

        // Midpoint for the line segment node position
        val midX = (fromPos.x + toPos.x) / 2f
        val midY = (fromPos.y + toPos.y) / 2f
        val midZ = (fromPos.z + toPos.z) / 2f

        // Length of the segment
        val dx = toPos.x - fromPos.x
        val dz = toPos.z - fromPos.z
        val length = kotlin.math.sqrt(dx * dx + dz * dz)

        // Angle of rotation
        val angle = Math.toDegrees(
            kotlin.math.atan2(dx.toDouble(), dz.toDouble())
        ).toFloat()

        try {
            val lineNode = ArNode().apply {
                position = Position(midX, midY, midZ)
                rotation = Rotation(0f, angle, 0f)
                // Scale: thin in X, very thin in Y, length in Z
                scale = Scale(0.03f, 0.01f, length.coerceAtLeast(0.1f))
                name = "line_$fromIdx"
            }

            sceneView.addChild(lineNode)
            lineNodes[fromIdx] = lineNode

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create line segment: ${e.message}", e)
        }
    }

    /**
     * Update an existing line segment position.
     */
    private fun updateLineSegment(
        lineNode: ArNode,
        route: List<Node>,
        fromIdx: Int,
        toIdx: Int,
        userX: Float,
        userY: Float
    ) {
        val fromPos = converter.nodeToWorldPosition(route[fromIdx], userX, userY,
            ARCoordinateConverter.LINE_HEIGHT)
        val toPos = converter.nodeToWorldPosition(route[toIdx], userX, userY,
            ARCoordinateConverter.LINE_HEIGHT)

        val midX = (fromPos.x + toPos.x) / 2f
        val midY = (fromPos.y + toPos.y) / 2f
        val midZ = (fromPos.z + toPos.z) / 2f

        val dx = toPos.x - fromPos.x
        val dz = toPos.z - fromPos.z
        val length = kotlin.math.sqrt(dx * dx + dz * dz)
        val angle = Math.toDegrees(
            kotlin.math.atan2(dx.toDouble(), dz.toDouble())
        ).toFloat()

        lineNode.position = Position(midX, midY, midZ)
        lineNode.rotation = Rotation(0f, angle, 0f)
        lineNode.scale = Scale(0.03f, 0.01f, length.coerceAtLeast(0.1f))
    }

    /**
     * Show arrival indicator at the destination.
     */
    fun showArrival(sceneView: ArSceneView, destination: Node, userX: Float, userY: Float) {
        clearAll(sceneView)

        val worldPos = converter.nodeToWorldPosition(destination, userX, userY, 0.5f)

        try {
            val arrivalNode = ArNode().apply {
                position = Position(worldPos.x, worldPos.y, worldPos.z)
                scale = Scale(0.5f, 0.5f, 0.5f)
                name = "arrival_marker"
            }

            sceneView.addChild(arrivalNode)
            arrowNodes[-1] = arrivalNode  // Special key for arrival

            Log.d(TAG, "Arrival marker placed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create arrival marker: ${e.message}", e)
        }
    }

    /**
     * Clear all 3D nodes from the scene.
     */
    fun clearAll(sceneView: ArSceneView) {
        for ((_, node) in arrowNodes) {
            sceneView.removeChild(node)
            node.destroy()
        }
        arrowNodes.clear()

        for ((_, node) in lineNodes) {
            sceneView.removeChild(node)
            node.destroy()
        }
        lineNodes.clear()

        isRendered = false
        lastRenderedIndex = -1
        Log.d(TAG, "All 3D route nodes cleared")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        arrowNodes.clear()
        lineNodes.clear()
    }
}
