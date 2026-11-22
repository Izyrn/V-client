package com.vclient.android.demo

/**
 * Things the player can carry. Items that [block] is not null can be placed in the world; tools have a [tool]
 * class and a [tier] (1 wooden, 2 stone, 3 iron). All names, colours and icons are original VYRN artwork.
 */
enum class Item(
    val id: Int,
    val label: String,
    val block: Block? = null,
    val tool: ToolClass = ToolClass.NONE,
    val tier: Int = 0,
    val maxStack: Int = 64,
    private val color: Int = 0x808080,
    val tag: String = "",
) {
    DIRT(1, "Dirt", Block.DIRT),
    COBBLE(2, "Cobblestone", Block.COBBLE),
    SAND(3, "Sand", Block.SAND),
    OAK_LOG(4, "Oak Log", Block.WOOD),
    CHERRY_LOG(5, "Cherry Log", Block.CHERRY_LOG),
    OAK_LEAVES(6, "Oak Leaves", Block.LEAVES),
    CHERRY_LEAVES(7, "Cherry Leaves", Block.CHERRY_LEAVES),
    PLANKS(8, "Planks", Block.PLANKS),
    CHERRY_PLANKS(9, "Cherry Planks", Block.CHERRY_PLANKS),
    TABLE(10, "Crafting Table", Block.TABLE),
    V_BLOCK(11, "V Block", Block.GOLD),
    IRON_ORE(12, "Iron Ore", Block.IRON_ORE),

    STICK(20, "Stick", color = 0x8b5a2b, tag = "/"),
    COAL(21, "Coal", color = 0x2a2a30, tag = "C"),
    IRON_INGOT(22, "Iron Ingot", color = 0xd8d8e0, tag = "I"),
    GOLD_NUGGET(23, "Gold Nugget", color = 0xe7bf68, tag = "G"),

    WOOD_PICK(30, "Wooden Pickaxe", tool = ToolClass.PICK, tier = 1, maxStack = 1, color = 0xb08a52, tag = "P"),
    WOOD_AXE(31, "Wooden Axe", tool = ToolClass.AXE, tier = 1, maxStack = 1, color = 0xb08a52, tag = "A"),
    WOOD_SHOVEL(32, "Wooden Shovel", tool = ToolClass.SHOVEL, tier = 1, maxStack = 1, color = 0xb08a52, tag = "S"),
    STONE_PICK(33, "Stone Pickaxe", tool = ToolClass.PICK, tier = 2, maxStack = 1, color = 0x8a8f99, tag = "P"),
    STONE_AXE(34, "Stone Axe", tool = ToolClass.AXE, tier = 2, maxStack = 1, color = 0x8a8f99, tag = "A"),
    STONE_SHOVEL(35, "Stone Shovel", tool = ToolClass.SHOVEL, tier = 2, maxStack = 1, color = 0x8a8f99, tag = "S"),
    IRON_PICK(36, "Iron Pickaxe", tool = ToolClass.PICK, tier = 3, maxStack = 1, color = 0xdcdce6, tag = "P"),
    IRON_AXE(37, "Iron Axe", tool = ToolClass.AXE, tier = 3, maxStack = 1, color = 0xdcdce6, tag = "A"),
    IRON_SHOVEL(38, "Iron Shovel", tool = ToolClass.SHOVEL, tier = 3, maxStack = 1, color = 0xdcdce6, tag = "S");

    /** Icon colour (0xRRGGBB): the block's own colour for placeable items. */
    val swatch: Int get() = block?.let { Textures.swatch(it) } ?: color

    val isTool: Boolean get() = tool != ToolClass.NONE

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: Int): Item? = byId[id]
    }
}
