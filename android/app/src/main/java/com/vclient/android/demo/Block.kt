package com.vclient.android.demo

/** The kind of tool that mines a block fastest. */
enum class ToolClass { NONE, PICK, AXE, SHOVEL }

/**
 * Block types of the VYRN Demo World. Everything here is original to VYRN: the names, the colours and
 * the procedural textures in [Textures]. [hardness] is seconds to break by hand; blocks with a [minTier] above 0
 * cannot be broken at all without a matching tool of at least that tier (1 wooden, 2 stone, 3 iron).
 */
enum class Block(
    val id: Int,
    val label: String,
    val solid: Boolean,
    val hardness: Double = 0.5,
    val tool: ToolClass = ToolClass.NONE,
    val minTier: Int = 0,
) {
    AIR(0, "Air", false),
    GRASS(1, "Grass", true, 0.6, ToolClass.SHOVEL),
    DIRT(2, "Dirt", true, 0.5, ToolClass.SHOVEL),
    STONE(3, "Stone", true, 3.0, ToolClass.PICK, 1),
    SAND(4, "Sand", true, 0.5, ToolClass.SHOVEL),
    WOOD(5, "Oak Log", true, 2.0, ToolClass.AXE),
    LEAVES(6, "Oak Leaves", true, 0.3),
    WATER(7, "Water", false),
    GOLD(8, "V Block", true, 2.5, ToolClass.PICK, 1),
    BLOSSOM_GRASS(9, "Blossom Grass", true, 0.6, ToolClass.SHOVEL),
    CHERRY_LOG(10, "Cherry Log", true, 2.0, ToolClass.AXE),
    CHERRY_LEAVES(11, "Cherry Leaves", true, 0.3),
    PLANKS(12, "Planks", true, 1.5, ToolClass.AXE),
    CHERRY_PLANKS(13, "Cherry Planks", true, 1.5, ToolClass.AXE),
    COBBLE(14, "Cobblestone", true, 2.5, ToolClass.PICK, 1),
    PATH(15, "Path", true, 0.6, ToolClass.SHOVEL),
    FARMLAND(16, "Farmland", true, 0.6, ToolClass.SHOVEL),
    CROP(17, "Crops", true, 0.2),
    TABLE(18, "Crafting Table", true, 1.5, ToolClass.AXE),
    COAL_ORE(19, "Coal Ore", true, 3.0, ToolClass.PICK, 1),
    IRON_ORE(20, "Iron Ore", true, 3.5, ToolClass.PICK, 2),
    GOLD_ORE(21, "Gold Ore", true, 4.0, ToolClass.PICK, 3);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): Block = byId[id] ?: AIR
    }
}

/** The two biomes of the demo island. */
enum class Biome(val label: String) {
    OAK_FOREST("Oak Forest"),
    CHERRY_BLOSSOM("Cherry Blossom"),
}
