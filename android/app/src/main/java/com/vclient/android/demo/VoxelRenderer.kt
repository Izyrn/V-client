package com.vclient.android.demo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where the player's eye is and which way it looks. [yaw] 0 faces +Z, [pitch] positive looks up. */
class Camera(val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double, val fovDegrees: Double = 74.0)

/**
 * A small CPU ray caster: one ray per pixel through the block grid, drawn at low resolution and scaled up by the
 * screen. It needs no GPU code, runs the same on every phone, and is fully unit-testable.
 */
class VoxelRenderer(private val world: DemoWorld) {
    /** The block the player is aiming at; its edges are outlined. */
    var selected: BlockHit? = null

    /** 0..1 digging progress of the aimed block; cracks darken its faces as it grows. */
    var breakProgress: Double = 0.0

    fun render(out: IntArray, w: Int, h: Int, cam: Camera) {
        val cy = cos(cam.yaw)
        val sy = sin(cam.yaw)
        val cp = cos(cam.pitch)
        val sp = sin(cam.pitch)
        // Camera basis: forward, right and up.
        val fx = sy * cp
        val fy = sp
        val fz = cy * cp
        val rx = cy
        val rz = -sy
        val ux = -sy * sp
        val uy = cp
        val uz = -cy * sp
        val tanHalf = tan(Math.toRadians(cam.fovDegrees) / 2)
        val aspect = w.toDouble() / h
        val underwater = world.get(floor(cam.x).toInt(), floor(cam.y).toInt(), floor(cam.z).toInt()) == Block.WATER

        for (py in 0 until h) {
            val ny = (1.0 - 2.0 * (py + 0.5) / h) * tanHalf
            for (px in 0 until w) {
                val nx = (2.0 * (px + 0.5) / w - 1.0) * aspect * tanHalf
                var dx = fx + rx * nx + ux * ny
                var dy = fy + uy * ny
                var dz = fz + rz * nx + uz * ny
                val inv = 1.0 / sqrt(dx * dx + dy * dy + dz * dz)
                dx *= inv; dy *= inv; dz *= inv
                out[py * w + px] = 0xff000000.toInt() or trace(cam.x, cam.y, cam.z, dx, dy, dz, underwater)
            }
        }
    }

    private fun trace(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, startUnderwater: Boolean): Int {
        var x = floor(ox).toInt()
        var y = floor(oy).toInt()
        var z = floor(oz).toInt()
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val inf = Double.POSITIVE_INFINITY
        val tDeltaX = if (dx == 0.0) inf else abs(1.0 / dx)
        val tDeltaY = if (dy == 0.0) inf else abs(1.0 / dy)
        val tDeltaZ = if (dz == 0.0) inf else abs(1.0 / dz)
        var tMaxX = if (dx == 0.0) inf else if (dx > 0) (x + 1 - ox) / dx else (ox - x) / -dx
        var tMaxY = if (dy == 0.0) inf else if (dy > 0) (y + 1 - oy) / dy else (oy - y) / -dy
        var tMaxZ = if (dz == 0.0) inf else if (dz > 0) (z + 1 - oz) / dz else (oz - z) / -dz

        var waterEnter = if (startUnderwater) 0.0 else -1.0
        var steps = 0
        while (steps++ < MAX_STEPS) {
            val t: Double
            var axis: Int
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; t = tMaxX; tMaxX += tDeltaX; axis = 0
            } else if (tMaxY < tMaxZ) {
                y += stepY; t = tMaxY; tMaxY += tDeltaY; axis = 1
            } else {
                z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; axis = 2
            }
            if (t > MAX_DIST) break
            if ((x < 0 && stepX < 0) || (x >= world.sx && stepX > 0) || (z < 0 && stepZ < 0) || (z >= world.sz && stepZ > 0) ||
                (y < 0 && stepY < 0) || (y >= world.sy && stepY > 0)
            ) break

            val id = world.getId(x, y, z)
            if (id == 0) continue
            if (id == Block.WATER.id) {
                if (waterEnter < 0) waterEnter = t
                continue
            }
            val block = Block.of(id)

            // Where on the block face the ray landed (u, v in 0..1) and which face it is.
            val px = ox + dx * t
            val py = oy + dy * t
            val pz = oz + dz * t
            val u: Double
            val v: Double
            val face: Int
            val shade: Double
            when (axis) {
                0 -> { u = frac(pz); v = 1 - frac(py); face = Textures.SIDE; shade = 0.82 }
                1 -> { u = frac(px); v = frac(pz); face = if (stepY < 0) Textures.TOP else Textures.BOTTOM; shade = if (stepY < 0) 1.0 else 0.52 }
                else -> { u = frac(px); v = 1 - frac(py); face = Textures.SIDE; shade = 0.68 }
            }
            val tx = (u * 8).toInt().coerceIn(0, 7)
            val ty = (v * 8).toInt().coerceIn(0, 7)
            var color = Textures.texel(block, face, tx, ty)
            color = scale(color, shade)

            val sel = selected
            if (sel != null && sel.x == x && sel.y == y && sel.z == z) {
                if (u < 0.06 || u > 0.94 || v < 0.06 || v > 0.94) {
                    color = mix(color, 0xffffff, 0.55)
                } else if (breakProgress > 0.0) {
                    // Cracks: more and more texels darken as digging progresses.
                    val crack = ((tx * 73856093) xor (ty * 19349663) xor (face * 83492791)) and 0xff
                    if (crack < breakProgress * 200) color = scale(color, 0.45)
                }
            }
            if (waterEnter >= 0) color = mix(color, WATER_COLOR, minOf(0.88, 0.40 + 0.09 * (t - waterEnter)))
            return mix(color, skyColor(dx, dy, dz), fogAmount(t))
        }

        val sky = skyColor(dx, dy, dz)
        return if (waterEnter >= 0) mix(sky, WATER_COLOR, 0.6) else sky
    }

    private fun fogAmount(t: Double): Double {
        val f = (t / FOG_DISTANCE).coerceIn(0.0, 1.0)
        return f * f
    }

    /** Night-sky gradient in VYRN's navy, with a few stars and a gold crescent moon. */
    private fun skyColor(dx: Double, dy: Double, dz: Double): Int {
        val k = (dy * 1.3).coerceIn(0.0, 1.0)
        var color = mix(HORIZON, ZENITH, k)
        if (dy > 0.05) {
            val key = ((dx * 170).toInt() * 73856093) xor ((dy * 170).toInt() * 19349663) xor ((dz * 170).toInt() * 83492791)
            if ((key and 0x7fffffff) % 700 == 0) color = mix(color, 0xffffff, 0.8)
        }
        val moon = dx * MOON_X + dy * MOON_Y + dz * MOON_Z
        val bite = dx * BITE_X + dy * BITE_Y + dz * BITE_Z
        if (moon > 0.9985 && bite < 0.9982) color = 0xfff0bd
        return color
    }

    private fun frac(d: Double) = d - floor(d)

    private fun scale(c: Int, f: Double): Int {
        val r = (((c shr 16) and 0xff) * f).toInt()
        val g = (((c shr 8) and 0xff) * f).toInt()
        val b = ((c and 0xff) * f).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        val r = (((a shr 16) and 0xff) * (1 - t) + ((b shr 16) and 0xff) * t).toInt()
        val g = (((a shr 8) and 0xff) * (1 - t) + ((b shr 8) and 0xff) * t).toInt()
        val bl = ((a and 0xff) * (1 - t) + (b and 0xff) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    private companion object {
        const val MAX_STEPS = 220
        const val MAX_DIST = 96.0
        const val FOG_DISTANCE = 80.0
        const val WATER_COLOR = 0x2a6ec0
        const val HORIZON = 0x3b5e9e
        const val ZENITH = 0x0a1030
        // Moon direction and the offset circle that carves it into a crescent (unit vectors).
        const val MOON_X = -0.4417
        const val MOON_Y = 0.5399
        const val MOON_Z = 0.7143
        const val BITE_X = -0.3980
        const val BITE_Y = 0.5800
        const val BITE_Z = 0.7100
    }
}
