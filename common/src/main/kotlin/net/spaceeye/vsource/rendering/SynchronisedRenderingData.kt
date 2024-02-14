package net.spaceeye.vsource.rendering

import com.google.common.base.Supplier
import com.mojang.blaze3d.vertex.PoseStack
import io.netty.buffer.Unpooled
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.spaceeye.vsource.LOG
import net.spaceeye.vsource.events.AVSEvents
import net.spaceeye.vsource.rendering.types.A2BRenderer
import net.spaceeye.vsource.rendering.types.RopeRenderer
import net.spaceeye.vsource.utils.*
import net.spaceeye.vsource.utils.dataSynchronization.ClientSynchronisedData
import net.spaceeye.vsource.utils.dataSynchronization.DataUnit
import net.spaceeye.vsource.utils.dataSynchronization.ServerChecksumsUpdatedPacket
import net.spaceeye.vsource.utils.dataSynchronization.ServerSynchronisedData
import org.valkyrienskies.mod.common.shipObjectWorld
import java.security.MessageDigest

fun mixinRunFn(poseStack: PoseStack, camera: Camera) {
    val level = Minecraft.getInstance().level!!
    for (ship in level.shipObjectWorld.loadedShips) {
        SynchronisedRenderingData.clientSynchronisedData.tryPoolDataUpdate(ship.id)
        for ((idx, render) in SynchronisedRenderingData.clientSynchronisedData.cachedData[ship.id] ?: continue) {
            render.renderData(poseStack, camera)
        }
    }
}

object RenderingTypes {
    private val strToIdx = mutableMapOf<String, Int>()
    private val suppliers = mutableListOf<Supplier<RenderingData>>()

    init {
        register { RopeRenderer() }
        register { A2BRenderer() }
    }

    private fun register(supplier: Supplier<RenderingData>) {
        suppliers.add(supplier)
        strToIdx[supplier.get().getTypeName()] = suppliers.size - 1
    }

    fun typeToIdx(type: String) = strToIdx[type]
    fun idxToSupplier(idx: Int) = suppliers[idx]
}

interface RenderingData: DataUnit {
    fun renderData(poseStack: PoseStack, camera: Camera)
}

class ClientSynchronisedRenderingData(getServerInstance: () -> ServerSynchronisedData<RenderingData>): ClientSynchronisedData<RenderingData>("rendering_data", getServerInstance)
class ServerSynchronisedRenderingData(getClientInstance: () -> ClientSynchronisedData<RenderingData>): ServerSynchronisedData<RenderingData>("rendering_data", getClientInstance) {
    //TODO switch from nbt to just directly writing to byte buffer?
    fun nbtSave(tag: CompoundTag): CompoundTag {
        val save = CompoundTag()

        LOG("SAVING RENDERING DATA")
        val point = getNow_ms()

        for ((k, v) in data) {
            val shipIdTag = CompoundTag()
            for ((id, item) in v) {
                val dataItemTag = CompoundTag()

                val typeIdx = RenderingTypes.typeToIdx(item.getTypeName())
                if (typeIdx == null) {
                    LOG("RENDERING ITEM WITH TYPE ${item.getTypeName()} RETURNED NULL TYPE INDEX DURING SAVING")
                    continue
                }

                dataItemTag.putInt("typeIdx", typeIdx)
                dataItemTag.putByteArray("data", item.serialize().accessByteBufWithCorrectSize())

                shipIdTag.put(id.toString(), dataItemTag)
            }
            save.put(k.toString(), shipIdTag)
        }

        LOG("FINISHING SAVING RENDERING DATA IN ${getNow_ms() - point} ms")

        tag.put("server_synchronised_data_${id}", save)
        return tag
    }

    fun nbtLoad(tag: CompoundTag) {
        if (!tag.contains("server_synchronised_data_${id}")) {return}
        val save = tag.get("server_synchronised_data_${id}") as CompoundTag

        LOG("LOADING RENDERING DATA")
        val point = getNow_ms()

        for (k in save.allKeys) {
            val shipIdTag = save.get(k) as CompoundTag
            val page = data.getOrPut(k.toLong()) { mutableMapOf() }
            for (kk in shipIdTag.allKeys) {
                val dataItemTag = shipIdTag[kk] as CompoundTag

                val typeIdx = dataItemTag.getInt("typeIdx")
                val item = RenderingTypes.idxToSupplier(typeIdx).get()
                try {
                    item.deserialize(FriendlyByteBuf(Unpooled.wrappedBuffer(dataItemTag.getByteArray("data"))))
                } catch (e: Exception) {
                    LOG("FAILED TO DESERIALIZE RENDER COMMAND OF SHIP ${page} WITH IDX ${typeIdx} AND TYPE ${item.getTypeName()}")
                    continue
                }

                page[kk.toInt()] = item
            }
        }
        LOG("FINISHING LOADING RENDERING DATA in ${getNow_ms() - point} ms")
    }
}

object SynchronisedRenderingData {
    lateinit var clientSynchronisedData: ClientSynchronisedRenderingData
    lateinit var serverSynchronisedData: ServerSynchronisedRenderingData

    //MD2, MD5, SHA-1, SHA-256, SHA-384, SHA-512
    val hasher = MessageDigest.getInstance("MD5")

    init {
        clientSynchronisedData = ClientSynchronisedRenderingData { serverSynchronisedData }
        serverSynchronisedData = ServerSynchronisedRenderingData { clientSynchronisedData }
        makeServerEvents()
    }

    private fun makeServerEvents() {
        AVSEvents.serverShipRemoveEvent.on {
            (shipData), handler ->
            serverSynchronisedData.data.remove(shipData.id)
            serverSynchronisedData.serverChecksumsUpdatedConnection()
                .sendToClients(
                    ServerLevelHolder.serverLevel!!.server.playerList.players,
                    ServerChecksumsUpdatedPacket(shipData.id, true)
                )
            LOG("SENT DELETED SHIPID")
        }
    }
}