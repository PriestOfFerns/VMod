package net.spaceeye.vsource.items

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.spaceeye.vsource.VSItems
import net.spaceeye.vsource.utils.Vector3d

//TODO find a way to detect if player it's a singular click or a button press

abstract class BaseTool: Item(Properties().tab(VSItems.TAB).stacksTo(1)) {
    abstract fun activatePrimaryFunction(level: Level, player: Player, clipResult: BlockHitResult)

    abstract fun resetState()

    override fun use(
        level: Level,
        player: Player,
        usedHand: InteractionHand
    ): InteractionResultHolder<ItemStack> {
        if (!level.isClientSide && player.isShiftKeyDown) {
            resetState()
        }

        val clipResult = level.clip(
            ClipContext(
                player.eyePosition,
                (Vector3d(player.eyePosition)
                        + Vector3d(player.lookAngle).snormalize() * 100).toMCVec3(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null
            )
        )

        activatePrimaryFunction(level, player, clipResult)

        return super.use(level, player, usedHand)
    }
}