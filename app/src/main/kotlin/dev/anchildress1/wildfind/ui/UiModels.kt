package dev.anchildress1.wildfind.ui

import dev.anchildress1.wildfind.core.hunt.PlantType

/**
 * What the camera screen looks for.
 *
 * @property row the target row, or null for the grass tutorial
 * @property name the target's common name
 * @property type the target's plant type
 * @property description what to look for, or null to show the type alone
 * @property number which find this is, 1-based; 0 for the tutorial
 * @property total the hunt's target count
 */
data class CameraTarget(
    val row: Int?,
    val name: String,
    val type: PlantType?,
    val number: Int,
    val total: Int,
    val description: String? = null,
)

/**
 * One find, or the grass tutorial passing.
 *
 * @property name what was found
 * @property found finds so far, the tutorial not counted
 * @property total the hunt's targets
 * @property next the next target's name, or null when none is left
 * @property tutorial the grass tutorial passed: no star
 * @property description what was found looks like, or null
 */
data class FoundInfo(
    val name: String,
    val found: Int,
    val total: Int,
    val next: String?,
    val tutorial: Boolean,
    val description: String? = null,
)
