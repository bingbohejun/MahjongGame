package doublemoon.mahjonggame.util

import net.minecraft.block.Block
import net.minecraft.util.shape.VoxelShape


/**
 * 使用 [Block.createCuboidShape],
 * 但是 终点座标 的地方,换成利用 方块尺寸 求出终点座标,
 * 方便使用 BlockBench 直接看著输入
 * */
fun boxBySize(
    posX: Int,
    posY: Int,
    posZ: Int,
    sizeX: Int,
    sizeY: Int,
    sizeZ: Int,
): VoxelShape {
    return boxBySize(
        posX.toDouble(),
        posY.toDouble(),
        posZ.toDouble(),
        sizeX.toDouble(),
        sizeY.toDouble(),
        sizeZ.toDouble()
    )
}

/**
 * 使用 [Block.createCuboidShape],
 * 但是 终点座标 的地方,换成利用 方块尺寸 求出终点座标,
 * 方便使用 BlockBench 直接看著输入
 * */
fun boxBySize(
    posX: Double,
    posY: Double,
    posZ: Double,
    sizeX: Double,
    sizeY: Double,
    sizeZ: Double,
): VoxelShape {
    return Block.createCuboidShape(
        posX,
        posY,
        posZ,
        posX + sizeX,
        posY + sizeY,
        posZ + sizeZ
    )
}