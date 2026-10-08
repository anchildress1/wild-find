package dev.anchildress1.wildfind.camera

/**
 * The viewfinder's size in pixels and the display's current `Surface.ROTATION_*`.
 *
 * @property width view width in pixels
 * @property height view height in pixels
 * @property rotation the display rotation
 */
data class ViewGeometry(val width: Int, val height: Int, val rotation: Int)
