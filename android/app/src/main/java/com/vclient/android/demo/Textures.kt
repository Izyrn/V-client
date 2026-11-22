package com.vclient.android.demo

/**
 * Procedural 8 x 8 block textures, drawn in code. They are original VYRN artwork: flat colours with hashed
 * speckle and simple patterns, so there are no image files and nothing is copied from any game.
 */
object Textures {
    const val TOP = 0
    const val BOTTOM = 1
    const val SIDE = 2

    private fun rgb(r: Int, g: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun noise(a: Int, b: Int, c: Int, d: Int): Int {
        var h = a * 374761393 + b * 668265263 + c * 1274126177 + d * 362437
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return h and 0x7fffffff
    }

    private fun dirt(speck: Int) = rgb(118 + speck, 84 + speck, 58 + speck / 2)

    private fun stone(speck: Int, dark: Int = 0) = rgb(124 + speck + dark, 129 + speck + dark, 140 + speck + dark)

    private fun planks(face: Int, iy: Int, speck: Int, r: Int, g: Int, b: Int): Int {
        val line = if (iy % 4 == 3) -26 else 0
        return rgb(r + line + speck / 2, g + line + speck / 2, b + line / 2 + speck / 3)
    }

    private fun ore(n: Int, speck: Int, spot: Int): Int =
        if (n % 7 < 2) spot else stone(speck)

    /** Colour (0xRRGGBB) of texel (ix, iy) in 0..7 on the given face (TOP, BOTTOM or SIDE) of a block. */
    fun texel(block: Block, face: Int, ix: Int, iy: Int): Int {
        val n = noise(block.id, face, ix, iy)
        val speck = (n % 29) - 14 // -14..14
        return when (block) {
            Block.GRASS -> when {
                face == TOP -> rgb(84 + speck, 158 + speck, 66 + speck / 2)
                face == SIDE && iy < 2 -> rgb(84 + speck, 150 + speck, 62)
                else -> dirt(speck)
            }
            Block.BLOSSOM_GRASS -> when {
                face == TOP -> if (n % 9 == 0) rgb(240, 168, 200) else rgb(132 + speck, 186 + speck, 104 + speck / 2)
                face == SIDE && iy < 2 -> if (n % 9 == 0) rgb(240, 168, 200) else rgb(132 + speck, 180 + speck, 100)
                else -> dirt(speck)
            }
            Block.DIRT -> dirt(speck)
            Block.STONE -> stone(speck, if (n % 7 == 0) -22 else 0)
            Block.SAND -> rgb(224 + speck / 2, 208 + speck / 2, 150 + speck / 2)
            Block.WOOD -> if (face == SIDE) {
                val stripe = if (ix % 3 == 0) -18 else 0
                rgb(112 + stripe + speck / 2, 80 + stripe + speck / 2, 46 + stripe / 2)
            } else {
                val ring = maxOf(Math.abs(ix - 3.5), Math.abs(iy - 3.5)).toInt()
                val tone = if (ring % 2 == 0) 22 else -6
                rgb(150 + tone, 112 + tone, 66 + tone / 2)
            }
            Block.CHERRY_LOG -> if (face == SIDE) {
                val stripe = if (ix % 3 == 0) -16 else 0
                rgb(98 + stripe + speck / 2, 60 + stripe + speck / 2, 62 + stripe / 2)
            } else {
                val ring = maxOf(Math.abs(ix - 3.5), Math.abs(iy - 3.5)).toInt()
                val tone = if (ring % 2 == 0) 20 else -8
                rgb(196 + tone, 146 + tone, 134 + tone)
            }
            Block.LEAVES -> if (n % 5 == 0) rgb(28, 78, 42) else rgb(44 + speck, 118 + speck, 58 + speck / 2)
            Block.CHERRY_LEAVES -> when {
                n % 11 == 0 -> rgb(252, 218, 232)
                n % 6 == 0 -> rgb(206, 118, 158)
                else -> rgb(244 + speck / 3, 170 + speck / 2, 200 + speck / 2)
            }
            Block.PLANKS -> planks(face, iy, speck, 178, 138, 84)
            Block.CHERRY_PLANKS -> planks(face, iy, speck, 214, 156, 146)
            Block.COBBLE -> {
                val seam = if (ix % 4 == 0 || iy % 4 == 0) -30 else 0
                stone(speck / 2, seam - 6)
            }
            Block.PATH -> rgb(172 + speck / 2, 142 + speck / 2, 98 + speck / 2)
            Block.FARMLAND -> if (face == TOP) {
                if (iy % 2 == 0) rgb(82 + speck / 2, 56 + speck / 2, 40) else rgb(104 + speck / 2, 74 + speck / 2, 50)
            } else dirt(speck)
            Block.CROP -> if (face == TOP) {
                rgb(206 + speck / 2, 184 + speck / 2, 66)
            } else {
                if (ix % 2 == 0) rgb(94 + speck / 2, 158 + speck / 2, 60) else rgb(136 + speck / 2, 188 + speck / 2, 72)
            }
            Block.TABLE -> when (face) {
                TOP -> if (ix == 3 || ix == 4 || iy == 3 || iy == 4) rgb(70, 48, 30) else planks(face, iy, speck, 178, 138, 84)
                SIDE -> if (iy < 2) rgb(70, 48, 30) else if (ix == 1 || ix == 6) rgb(100, 74, 46) else planks(face, iy, speck, 190, 146, 90)
                else -> planks(face, iy, speck, 150, 112, 66)
            }
            Block.COAL_ORE -> ore(n, speck, rgb(26, 26, 30))
            Block.IRON_ORE -> ore(n, speck, rgb(214, 168, 128))
            Block.GOLD_ORE -> ore(n, speck, rgb(240, 200, 70))
            Block.GOLD -> {
                // VYRN's own block: gold with a dark "V" on every face.
                val onV = (iy in 0..5 && (ix == iy / 2 || ix == 7 - iy / 2)) || (iy in 6..7 && (ix == 3 || ix == 4))
                if (onV) rgb(16, 19, 33) else rgb(231 + speck / 2, 191 + speck / 2, 104 + speck / 2)
            }
            Block.WATER -> rgb(40, 110, 190)
            Block.AIR -> rgb(0, 0, 0)
        }
    }

    /** Average colour of a block's top face, for hotbar swatches and item icons. */
    fun swatch(block: Block): Int {
        var r = 0
        var g = 0
        var b = 0
        for (iy in 0..7) for (ix in 0..7) {
            val c = texel(block, TOP, ix, iy)
            r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
        }
        return rgb(r / 64, g / 64, b / 64)
    }
}
