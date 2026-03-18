package com.holonav.app.ar

import com.holonav.app.map.Node
import kotlin.math.atan2

/**
 * Converts 2D indoor map coordinates to 3D AR world positions.
 *
 * Map coordinate system: 1000×700 virtual canvas, units ≈ 3 per meter
 * AR coordinate system:  X = right, Y = up, Z = backward (camera looks -Z)
 *
 * The user's current position is treated as the origin (0, 0, 0).
 * Each waypoint is positioned relative to the user.
 */
class ARCoordinateConverter {

    companion object {
        /** Map units per real-world meter */
        const val MAP_UNITS_PER_METER = 3f

        /** Height of arrows above the floor plane (meters) */
        const val ARROW_HEIGHT = 0.3f

        /** Height of route line above the floor plane (meters) */
        const val LINE_HEIGHT = 0.05f

        /** Maximum render distance (meters) — don't render waypoints beyond this */
        const val MAX_RENDER_DISTANCE = 20f

        /** Maximum number of waypoints to render ahead */
        const val MAX_VISIBLE_WAYPOINTS = 4
    }

    /**
     * 3D position in AR world space.
     */
    data class WorldPosition(
        val x: Float,   // Right
        val y: Float,   // Up
        val z: Float    // Backward (negative = forward from camera)
    )

    /**
     * Convert a map node to a 3D position relative to the user.
     *
     * Map X → AR X (right/left)
     * Map Y → AR Z (forward/backward, negated because camera looks -Z)
     * AR Y is constant (height above floor)
     */
    fun nodeToWorldPosition(
        node: Node,
        userX: Float,
        userY: Float,
        height: Float = ARROW_HEIGHT
    ): WorldPosition {
        val dx = (node.x - userX) / MAP_UNITS_PER_METER
        val dz = -(node.y - userY) / MAP_UNITS_PER_METER  // Negate: map Y+ is down, AR Z- is forward
        return WorldPosition(dx, height, dz)
    }

    /**
     * Get the rotation angle (Y-axis, in degrees) for an arrow
     * pointing from one node toward the next node.
     */
    fun getArrowRotation(from: Node, to: Node): Float {
        val dx = (to.x - from.x).toDouble()
        val dz = -(to.y - from.y).toDouble()
        return Math.toDegrees(atan2(dx, dz)).toFloat()
    }

    /**
     * Filter route to only include visible waypoints from currentIndex.
     * Returns at most MAX_VISIBLE_WAYPOINTS nodes within MAX_RENDER_DISTANCE.
     */
    fun getVisibleWaypoints(
        route: List<Node>,
        currentIndex: Int,
        userX: Float,
        userY: Float
    ): List<Int> {
        val visible = mutableListOf<Int>()
        for (i in currentIndex until route.size) {
            if (visible.size >= MAX_VISIBLE_WAYPOINTS) break

            val node = route[i]
            val distMeters = node.distanceTo2D(userX, userY) / MAP_UNITS_PER_METER
            if (distMeters > MAX_RENDER_DISTANCE) break

            visible.add(i)
        }
        return visible
    }

    /**
     * Check if user has passed a waypoint (within threshold).
     */
    fun hasReachedWaypoint(
        node: Node,
        userX: Float,
        userY: Float,
        thresholdMeters: Float = 2f
    ): Boolean {
        val distMeters = node.distanceTo2D(userX, userY) / MAP_UNITS_PER_METER
        return distMeters < thresholdMeters
    }

    /**
     * Helper: distance from a node to a point.
     */
    private fun Node.distanceTo2D(px: Float, py: Float): Float {
        val dx = x - px
        val dy = y - py
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
