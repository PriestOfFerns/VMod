package net.spaceeye.vmod.toolgun.modes

import dev.architectury.event.EventResult
import dev.architectury.networking.NetworkManager
import gg.essential.elementa.components.UIBlock
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.spaceeye.vmod.WLOG
import net.spaceeye.vmod.constraintsManaging.addFor
import net.spaceeye.vmod.networking.C2SConnection
import net.spaceeye.vmod.rendering.types.A2BRenderer
import net.spaceeye.vmod.utils.*
import net.spaceeye.vmod.constraintsManaging.makeManagedConstraint
import net.spaceeye.vmod.constraintsManaging.types.AxisMConstraint
import net.spaceeye.vmod.guiElements.*
import net.spaceeye.vmod.translate.GUIComponents.COMPLIANCE
import net.spaceeye.vmod.translate.GUIComponents.MAX_FORCE
import net.spaceeye.vmod.translate.get
import org.lwjgl.glfw.GLFW
import java.awt.Color
import net.spaceeye.vmod.limits.DoubleLimit
import net.spaceeye.vmod.limits.ServerLimits
import net.spaceeye.vmod.networking.S2CConnection
import net.spaceeye.vmod.networking.Serializable
import net.spaceeye.vmod.toolgun.ClientToolGunState
import net.spaceeye.vmod.transformProviders.PlacementAssistTransformProvider
import net.spaceeye.vmod.utils.Ref
import net.spaceeye.vmod.transformProviders.RotationAssistTransformProvider
import net.spaceeye.vmod.translate.GUIComponents.AXIS
import net.spaceeye.vmod.translate.GUIComponents.CENTERED_IN_BLOCK
import net.spaceeye.vmod.translate.GUIComponents.CENTERED_ON_SIDE
import net.spaceeye.vmod.translate.GUIComponents.DISABLE_COLLISIONS
import net.spaceeye.vmod.translate.GUIComponents.DISTANCE_FROM_BLOCK
import net.spaceeye.vmod.translate.GUIComponents.FIXED_DISTANCE
import net.spaceeye.vmod.translate.GUIComponents.HITPOS_MODES
import net.spaceeye.vmod.translate.GUIComponents.NORMAL
import net.spaceeye.vmod.translate.GUIComponents.WIDTH
import org.joml.AxisAngle4d
import org.joml.Quaterniond
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.apigame.constraints.VSHingeOrientationConstraint
import org.valkyrienskies.core.impl.game.ShipTeleportDataImpl
import org.valkyrienskies.core.impl.game.ships.ShipTransformImpl
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

object AxisNetworking {
    private var obj: AxisMode? = null
    fun init(mode: AxisMode) {if (obj == null) { obj = mode} }

    val s2cHandleFailure = "handle_failure" idWithConns {
        object : S2CConnection<S2CHandleFailurePacket>(it, "axis_networking") {
            override fun clientHandler(buf: FriendlyByteBuf, context: NetworkManager.PacketContext) {
                val obj = obj ?: return
                obj.resetState()
            }
        }
    }

    class S2CHandleFailurePacket(): Serializable {
        override fun serialize(): FriendlyByteBuf { return getBuffer() }
        override fun deserialize(buf: FriendlyByteBuf) {}
    }

    private infix fun <TT: Serializable> String.idWithConns(constructor: (String) -> S2CConnection<TT>): S2CConnection<TT> {
        val instance = constructor(this)
        try { // Why? so that if it's registered on dedicated client/server it won't die
            NetworkManager.registerReceiver(instance.side, instance.id, instance.getHandler())
        } catch(e: NoSuchMethodError) {}
        return instance
    }
}

class AxisMode : BaseMode {

    var compliance: Double = 1e-20
    var maxForce: Double = 1e10
    var width: Double = .2

    var fixedDistance: Double = -1.0
    var disableCollisions: Boolean = false
    var distanceFromBlock = 0.01

    var posMode = PositionModes.NORMAL
    var primaryStage = ThreeClicksActivationSteps.FIRST_RAYCAST
    var secondaryFirstdRaycast = false

    var primaryAngle = Ref(0.0)

    init {
        AxisNetworking.init(this)
    }

    override fun handleKeyEvent(key: Int, scancode: Int, action: Int, mods: Int): EventResult {
        if (primaryStage == ThreeClicksActivationSteps.FIRST_RAYCAST && !secondaryFirstdRaycast) { return EventResult.pass() }

        if (ClientToolGunState.TOOLGUN_RESET_KEY.matches(key, scancode)) {
            primaryStage = ThreeClicksActivationSteps.FIRST_RAYCAST
            resetState()
        }

        return EventResult.interruptFalse()
    }

    override fun handleMouseButtonEvent(button: Int, action: Int, mods: Int): EventResult {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS) {
            clientHandlePrimary()
            conn_primary.sendToServer(this)
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && action == GLFW.GLFW_PRESS) {
            clientHandleSecondary()
            conn_secondary.sendToServer(this)
        }

        return EventResult.interruptFalse()
    }

    override fun handleMouseScrollEvent(amount: Double): EventResult {
        if (primaryStage != ThreeClicksActivationSteps.FINALIZATION) { return EventResult.pass() }

        primaryAngle.it = primaryAngle.it + amount * 0.2

        return EventResult.interruptFalse()
    }

    private fun clientHandleSecondary() {
        secondaryFirstdRaycast = !secondaryFirstdRaycast
    }

    var caughtShip: ClientShip? = null

    private fun clientHandlePrimary() {
        when (primaryStage) {
            ThreeClicksActivationSteps.FIRST_RAYCAST  -> clientPrimaryFirst()
            ThreeClicksActivationSteps.SECOND_RAYCAST -> clientPrimarySecond()
            ThreeClicksActivationSteps.FINALIZATION   -> clientPrimaryThird()
        }
    }

    private fun clientPrimaryFirst() {
        if (caughtShip != null) {
            caughtShip!!.transformProvider = null
            caughtShip = null
            return
        }

        val raycastResult = RaycastFunctions.raycast(
            Minecraft.getInstance().level!!,
            RaycastFunctions.Source(
                Vector3d(Minecraft.getInstance().gameRenderer.mainCamera.lookVector).snormalize(),
                Vector3d(Minecraft.getInstance().player!!.eyePosition)
            )
        )

        val level = Minecraft.getInstance().level!!

        if (raycastResult.state.isAir) {return}
        val mode = if (posMode != PositionModes.CENTERED_IN_BLOCK) {posMode} else {PositionModes.CENTERED_ON_SIDE}

        caughtShip = (level.getShipManagingPos(raycastResult.blockPosition) ?: return) as ClientShip
        caughtShip!!.transformProvider = PlacementAssistTransformProvider(raycastResult, mode, caughtShip!!)

        primaryStage = ThreeClicksActivationSteps.SECOND_RAYCAST
        return
    }

    private fun clientPrimarySecond() {
        primaryStage = ThreeClicksActivationSteps.FINALIZATION
        if (caughtShip == null) { return }

        val placementTransform = caughtShip!!.transformProvider
        if (placementTransform !is PlacementAssistTransformProvider) {return}

        primaryAngle.it = 0.0
        caughtShip!!.transformProvider = RotationAssistTransformProvider(placementTransform, primaryAngle)
    }

    private fun clientPrimaryThird() {
        primaryStage = ThreeClicksActivationSteps.FIRST_RAYCAST
        if (caughtShip != null) {
            caughtShip!!.transformProvider = null
            caughtShip = null
        }
    }

    override fun serialize(): FriendlyByteBuf {
        val buf = getBuffer()

        buf.writeDouble(compliance)
        buf.writeDouble(maxForce)
        buf.writeEnum(posMode)
        buf.writeDouble(width)
        buf.writeBoolean(disableCollisions)
        buf.writeDouble(distanceFromBlock)

        buf.writeBoolean(secondaryFirstdRaycast)

        buf.writeEnum(primaryStage)
        buf.writeDouble(primaryAngle.it)

        return buf
    }

    override fun deserialize(buf: FriendlyByteBuf) {
        compliance = buf.readDouble()
        maxForce = buf.readDouble()
        posMode = buf.readEnum(posMode.javaClass)
        width = buf.readDouble()
        disableCollisions = buf.readBoolean()
        distanceFromBlock = buf.readDouble()

        secondaryFirstdRaycast = buf.readBoolean()

        primaryStage = buf.readEnum(primaryStage.javaClass)
        primaryAngle.it = buf.readDouble()
    }

    override fun serverSideVerifyLimits() {
        val limits = ServerLimits.instance
        compliance = limits.compliance.get(compliance)
        maxForce = limits.maxForce.get(maxForce)
        fixedDistance = limits.fixedDistance.get(fixedDistance)
        distanceFromBlock = limits.distanceFromBlock.get(distanceFromBlock)
    }

    override val itemName = AXIS
    override fun makeGUISettings(parentWindow: UIBlock) {
        val offset = 2.0f
        val limits = ServerLimits.instance

        makeTextEntry(COMPLIANCE.get(), ::compliance, offset, offset, parentWindow, limits.compliance)
        makeTextEntry(MAX_FORCE.get(),  ::maxForce,   offset, offset, parentWindow, limits.maxForce)
        makeTextEntry(WIDTH.get(),      ::width,      offset, offset, parentWindow, DoubleLimit(0.0, 1.0))
        makeTextEntry(FIXED_DISTANCE.get(),     ::fixedDistance,     offset, offset, parentWindow)
        makeCheckBox (DISABLE_COLLISIONS.get(), ::disableCollisions, offset, offset, parentWindow)
        makeTextEntry(DISTANCE_FROM_BLOCK.get(),::distanceFromBlock, offset, offset, parentWindow, limits.distanceFromBlock)
        makeDropDown(HITPOS_MODES.get(), parentWindow, offset, offset, listOf(
            DItem(NORMAL.get(),            posMode == PositionModes.NORMAL)            { posMode = PositionModes.NORMAL },
            DItem(CENTERED_ON_SIDE.get(),  posMode == PositionModes.CENTERED_ON_SIDE)  { posMode = PositionModes.CENTERED_ON_SIDE },
            DItem(CENTERED_IN_BLOCK.get(), posMode == PositionModes.CENTERED_IN_BLOCK) { posMode = PositionModes.CENTERED_IN_BLOCK },
        ))
    }

    val conn_secondary = register { object : C2SConnection<AxisMode>("axis_mode_secondary", "toolgun_command") { override fun serverHandler(buf: FriendlyByteBuf, context: NetworkManager.PacketContext) = serverRaycastAndActivate<AxisMode>(context.player, buf, ::AxisMode) { item, serverLevel, player, raycastResult -> item.activateSecondaryFunction(serverLevel, player, raycastResult) } } }
    val conn_primary   = register { object : C2SConnection<AxisMode>("axis_mode_primary",   "toolgun_command") { override fun serverHandler(buf: FriendlyByteBuf, context: NetworkManager.PacketContext) = serverRaycastAndActivate<AxisMode>(context.player, buf, ::AxisMode) {
            item, serverLevel, player, raycastResult ->
        activatePrimaryFunction(serverLevel, player, raycastResult)
    } } }

    var previousResult: RaycastFunctions.RaycastResult? = null

    fun activateSecondaryFunction(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) = serverRaycast2PointsFnActivation(posMode, level, raycastResult, { if (previousResult == null || secondaryFirstdRaycast) { previousResult = it; Pair(false, null) } else { Pair(true, previousResult) } }, ::resetState) {
        level, shipId1, shipId2, ship1, ship2, spoint1, spoint2, rpoint1, rpoint2, prresult, rresult ->

        level.makeManagedConstraint(AxisMConstraint(
            spoint1, spoint2, rpoint1, rpoint2,
            ship1, ship2, shipId1, shipId2,
            compliance, maxForce,
            fixedDistance,
            disableCollisions,
            listOf(prresult.blockPosition, rresult.blockPosition),
            A2BRenderer(
                ship1 != null,
                ship2 != null,
                spoint1, spoint2,
                Color(0, 200, 0),
                width
            )
        )).addFor(player)

        resetState()
    }

    var firstResult: RaycastFunctions.RaycastResult? = null
    var secondResult: RaycastFunctions.RaycastResult? = null

    fun activatePrimaryFunction(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (level !is ServerLevel) {throw RuntimeException("Function intended for server use only was activated on client. How.")}
        when (primaryStage) {
            ThreeClicksActivationSteps.SECOND_RAYCAST -> primaryFunctionFirst (level, player, raycastResult)
            ThreeClicksActivationSteps.FINALIZATION   -> primaryFunctionSecond(level, player, raycastResult)
            ThreeClicksActivationSteps.FIRST_RAYCAST  -> primaryFunctionThird (level, player, raycastResult)
        }
    }

    private fun handleFailure(player: Player) {
        AxisNetworking.s2cHandleFailure.sendToClient(player as ServerPlayer, AxisNetworking.S2CHandleFailurePacket())
        resetState()
    }

    private fun primaryFunctionFirst(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (raycastResult.state.isAir) {return handleFailure(player)}
        if (level.getShipManagingPos(raycastResult.blockPosition) == null) {return handleFailure(player)}
        firstResult = raycastResult
    }

    private fun primaryFunctionSecond(level: Level, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (raycastResult.state.isAir) {return handleFailure(player) }
        val ship = level.getShipManagingPos(raycastResult.blockPosition)
        if (ship == level.getShipManagingPos(firstResult?.blockPosition ?: return handleFailure(player))) {return handleFailure(player)}
        secondResult = raycastResult
    }

    private fun primaryFunctionThird(level: ServerLevel, player: Player, raycastResult: RaycastFunctions.RaycastResult) {
        if (firstResult == null || secondResult == null) {return handleFailure(player)}

        val firstResult = firstResult!!
        val secondResult = secondResult!!

        val ship1 = level.getShipManagingPos(firstResult.blockPosition)
        val ship2 = level.getShipManagingPos(secondResult.blockPosition)

        if (ship1 == null) {return handleFailure(player)}
        if (ship1 == ship2) {return handleFailure(player)}

//        var dir1 = firstResult.globalNormalDirection!!
//        var dir2 = if (ship2 != null) { transformDirectionShipToWorld(ship2, secondResult.globalNormalDirection!!) } else secondResult.globalNormalDirection!!
        var dir2 = secondResult.worldNormalDirection!!

        // not sure why i need to flip normal but it works
        val dir1 =  when {
            firstResult.globalNormalDirection!!.y ==  1.0 -> -firstResult.globalNormalDirection!!
            firstResult.globalNormalDirection!!.y == -1.0 -> -firstResult.globalNormalDirection!!
            else -> firstResult.globalNormalDirection!!
        }

        val angle = Quaterniond(AxisAngle4d(primaryAngle.it, dir2.toJomlVector3d()))
        val rotation1 = getQuatFromDir(dir1).normalize()
        val rotation2 = getQuatFromDir(dir2).normalize()

        val rotation = Quaterniond()
            .mul(angle)
            .mul(rotation2)
            .mul(rotation1)
            .normalize()


        val (spoint1, spoint2) = getModePositions(if (posMode == PositionModes.NORMAL) {posMode} else {PositionModes.CENTERED_ON_SIDE}, firstResult, secondResult)
        var rpoint2 = if (ship2 == null) spoint2 else posShipToWorld(ship2, Vector3d(spoint2))

        // rotation IS IMPORTANT, so make a new transform with new rotation to translate points
        val newTransform = (ship1.transform as ShipTransformImpl).copy(shipToWorldRotation = rotation)

        // we cannot modify position in ship, we can, however, modify position in world
        // this translates ship so that after teleportation its spoint1 will be at rpoint2
        val point = rpoint2 - (
            posShipToWorld(ship1, spoint1, newTransform) - Vector3d(ship1.transform.positionInWorld)
        )

        level.shipObjectWorld.teleportShip(
            ship1, ShipTeleportDataImpl(
                point.toJomlVector3d(), rotation, org.joml.Vector3d()
            )
        )

        rpoint2 = if (ship2 == null) spoint2 else posShipToWorld(ship2, Vector3d(spoint2))
        val rpoint1 = Vector3d(rpoint2) + dir2.normalize() * distanceFromBlock

        val shipId1: ShipId = ship1.id
        val shipId2: ShipId = ship2?.id ?: level.shipObjectWorld.dimensionToGroundBodyIdImmutable[level.dimensionId]!!

        val constraint = AxisMConstraint(
            spoint1, spoint2, rpoint1, rpoint2, ship1, ship2, shipId1, shipId2,
            compliance, maxForce, fixedDistance, disableCollisions,
            listOf(firstResult.blockPosition, secondResult.blockPosition)
        )

        level.makeManagedConstraint(constraint).addFor(player)
        resetState()
    }

    fun resetState() {
        primaryStage = ThreeClicksActivationSteps.FIRST_RAYCAST
        if (caughtShip != null) {
            caughtShip!!.transformProvider = null
            caughtShip = null
        }
        previousResult = null
        firstResult = null
        secondResult = null
        secondaryFirstdRaycast = false
    }
}