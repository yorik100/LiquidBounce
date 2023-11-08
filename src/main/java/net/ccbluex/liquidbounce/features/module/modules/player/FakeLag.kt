/*
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.EventTarget
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.PacketEvent
import net.ccbluex.liquidbounce.event.WorldEvent
import net.ccbluex.liquidbounce.event.Render3DEvent
import net.ccbluex.liquidbounce.event.UpdateEvent
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.modules.player.Blink;
import net.ccbluex.liquidbounce.features.module.modules.render.Breadcrumbs
import net.ccbluex.liquidbounce.utils.PacketUtils.sendPacket
import net.ccbluex.liquidbounce.utils.render.ColorUtils.rainbow
import net.ccbluex.liquidbounce.utils.render.RenderUtils.glColor
import net.ccbluex.liquidbounce.utils.timing.MSTimer
import net.ccbluex.liquidbounce.utils.extensions.*
import net.ccbluex.liquidbounce.value.BoolValue
import net.ccbluex.liquidbounce.value.IntegerValue
import net.ccbluex.liquidbounce.value.FloatValue
import net.ccbluex.liquidbounce.value.ListValue
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.network.Packet
import net.minecraft.network.play.client.*
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S40PacketDisconnect
import net.minecraft.network.play.server.S06PacketUpdateHealth
import net.minecraft.network.play.server.S29PacketSoundEffect
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.play.server.S02PacketChat
import net.minecraft.network.status.client.C01PacketPing
import net.minecraft.network.handshake.client.C00Handshake
import net.minecraft.network.status.client.C00PacketServerQuery
import net.minecraft.item.EnumAction
import net.minecraft.util.Vec3
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import java.util.*
import java.util.LinkedHashMap

object FakeLag : Module("FakeLag", ModuleCategory.PLAYER, gameDetecting = false) {

    private val packetQueue = LinkedHashMap<Packet<*>, Long>()
    private val positions = LinkedHashMap<Vec3, Long>()
    private val delay by IntegerValue("Delay", 550, 0..1000)
    private val recoilTime by IntegerValue("RecoilTime", 750, 0..2000)
    private val distanceToPlayers by FloatValue("AllowedDistanceToPlayers", 3.5f, 0.0f..6.0f)
    private val resetTimer = MSTimer()
    private var wasNearPlayer = false

    override fun onDisable() {
        if (mc.thePlayer == null)
            return

        blink()
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val packet = event.packet

        if (mc.thePlayer == null || mc.thePlayer.isDead)
            return

        if (event.isCancelled)
            return

        if (distanceToPlayers > 0.0 && wasNearPlayer)
            return

        when (packet) {
            is C00Handshake, is C00PacketServerQuery, is C01PacketPing -> {
                return
            }

            // Flush on doing action, getting action
            is S08PacketPlayerPosLook, is C08PacketPlayerBlockPlacement, is C07PacketPlayerDigging, is C12PacketUpdateSign, is C02PacketUseEntity, is C19PacketResourcePackStatus -> {
                blink()
                return
            }

            // Flush on kb
            is S12PacketEntityVelocity -> {
                if (mc.thePlayer.entityId == packet.entityID && (packet.motionY != 0 || packet.motionX != 0 || packet.motionZ != 0)) {
                        blink()
                        return
                }
            }
            is S27PacketExplosion -> {
                if (packet.field_149153_g != 0f || packet.field_149152_f != 0f || packet.field_149159_h != 0f) {
                        blink()
                        return
                }
            }

            // Flush on damage
            is S06PacketUpdateHealth -> {
                if (packet.getHealth() < mc.thePlayer.getHealth()) {
                    blink()
                    return
                }
            }
        }

        if (!resetTimer.hasTimePassed(recoilTime))
            return

        if (event.eventType == EventState.SEND) {
            event.cancelEvent()
            if (packet is C03PacketPlayer && packet.isMoving) {
                val packetPos = Vec3(packet.x, packet.y, packet.z)
                positions[packetPos] = System.currentTimeMillis()
            }
            packetQueue[packet] = System.currentTimeMillis()
        }
    }

    @EventTarget
    fun onWorld(event: WorldEvent) {
        // Clear packets on disconnect only
        if (event.worldClient == null) {
            blink(false)
        }
    }

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        val thePlayer = mc.thePlayer ?: return

        if (distanceToPlayers > 0.0) {
            val filtered = positions.keys.toList()
            var serverPos = filtered.firstOrNull()
            if (serverPos == null)
                serverPos = Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)
            val otherPlayers = mc.theWorld.playerEntities.filter { it != mc.thePlayer }
            val playerBox = thePlayer.entityBoundingBox.offset(serverPos.xCoord - thePlayer.posX, serverPos.yCoord - thePlayer.posY, serverPos.zCoord - thePlayer.posZ)
            wasNearPlayer = false
            for (player in otherPlayers) {
                val eyePos = Vec3(player.posX, player.posY + 1.62, player.posZ)
                if (eyePos.distanceTo(getNearestPointBB(eyePos, playerBox)) <= distanceToPlayers.toDouble()) {
                    blink()
                    wasNearPlayer = true
                    return
                }
            }
        }
        val module = Blink
        if (module.blinkingSend() || mc.thePlayer.isDead || thePlayer.isUsingItem)
        {
            blink()
            return
        }

        if (!resetTimer.hasTimePassed(recoilTime))
            return

        handlePackets()
    }

    @EventTarget
    fun onRender3D(event: Render3DEvent) {
        val color =
            if (Breadcrumbs.colorRainbow) rainbow()
            else Color(Breadcrumbs.colorRed, Breadcrumbs.colorGreen, Breadcrumbs.colorBlue)

        val filtered = positions.keys.toList()

        val module = Blink

        if (module.blinkingSend())
            return

        synchronized(filtered) {
            glPushMatrix()
            glDisable(GL_TEXTURE_2D)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glEnable(GL_LINE_SMOOTH)
            glEnable(GL_BLEND)
            glDisable(GL_DEPTH_TEST)
            mc.entityRenderer.disableLightmap()
            glBegin(GL_LINE_STRIP)
            glColor(color)

            val renderPosX = mc.renderManager.viewerPosX
            val renderPosY = mc.renderManager.viewerPosY
            val renderPosZ = mc.renderManager.viewerPosZ

            for (pos in filtered)
                glVertex3d(pos.xCoord - renderPosX, pos.yCoord - renderPosY, pos.zCoord - renderPosZ)

            glColor4d(1.0, 1.0, 1.0, 1.0)
            glEnd()
            glEnable(GL_DEPTH_TEST)
            glDisable(GL_LINE_SMOOTH)
            glDisable(GL_BLEND)
            glEnable(GL_TEXTURE_2D)
            glPopMatrix()
        }
    }

    override val tag
        get() = packetQueue.size.toString()

    private fun blink(handlePackets: Boolean = true) {
        if (handlePackets) {
            resetTimer.reset()
            val filtered = packetQueue.keys.toList()

            for (packet in filtered) {
                sendPacket(packet, false)
                packetQueue.remove(packet)
            }
        } else {
            packetQueue.clear()
        }
        positions.clear()
    }

    private fun handlePackets() {
        val filtered = packetQueue.entries.filter { (key: Packet<*>, value: Long) -> value <= (System.currentTimeMillis() - delay) }.toList()

        filtered.forEach { entry ->
            val packet = entry.key
            sendPacket(packet, false)
            packetQueue.remove(packet)
        }

        val filtered2 = positions.entries.filter { (key: Vec3, value: Long) -> value <= (System.currentTimeMillis() - delay) }.toList()
        filtered2.forEach { entry ->
            val position = entry.key
            positions.remove(position)
        }
    }

}
