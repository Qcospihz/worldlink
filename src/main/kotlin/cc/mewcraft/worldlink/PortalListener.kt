package cc.mewcraft.worldlink

import io.papermc.paper.event.entity.EntityPortalReadyEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter
import org.bukkit.*
import org.bukkit.World.Environment
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent

// NamespacedKey of default worlds (no matter what the `level-name` is)
private val OVERWORLD_KEY = NamespacedKey.minecraft("overworld")
private val THE_NETHER_KEY = NamespacedKey.minecraft("the_nether")
private val THE_END_KEY = NamespacedKey.minecraft("the_end")

/**
 * This listener fixes the issue where Nether/End Portals do not function properly in custom worlds.
 *
 * ### Expected behavior
 * If we have three custom worlds named `resrc` (Normal), `resrc_nether` (Nether), `resrc_the_end` (End),
 * the possible direction of Nether/End Portals should work as illustrated as the following:
 *
 * - resrc -> [ resrc_nether, resrc_the_end ]
 * - resrc_nether -> [ resrc ]
 * - resrc_the_end -> [ resrc ]
 *
 * The arrow means, if the entity comes into contact with the portal in the world on LHS, it will teleport to the world on RHS.
 * If RHS has more than one world, it will teleport to the first correct world depending on the portal type (Nether/End Portals)
 *
 * ### Actual behavior (Nether Portals)
 * Nether Portals in `resrc` and `resrc_nether` do not trigger any teleport at all.
 * All they have is only the visual effect when players are standing inside Nether Portals.
 * What's worse, events including [PlayerPortalEvent] and [EntityPortalEvent] do not get triggered.
 *
 * ### Actual behavior (End Portals)
 * End Portals in `resrc` teleport to the default end, not `resrc_the_end`.
 * End Portals in `resrc_the_end` teleport to the default normal, not `resrc`.
 *
 * ### Implementation Details
 * All the functions do not modify the Nether/End Portals in default worlds.
 * All the functions only modify the Nether/End Portals in custom worlds.
 * Function [onPortalReady] modifies the target world of Nether Portals.
 * Function [onPlayerPortal] modifies the location scale of Nether Portals and the target world of End Portals for players.
 * Function [onEntityPortal] does the same thing as [onPlayerPortal] but for non-player entities.
 */
class PortalListener(
    private val nameLinks: WorldNameLinks,
) : Listener {
    /**
     * **This handler modifies target world for Nether Portals.**
     *
     * ### Problem
     * Normally, the Nether Portals in custom worlds do not function at all.
     * Also, they trigger neither [PlayerPortalEvent] nor [EntityPortalEvent].
     * The behaviors of the two events are not the same as that if the worlds are default.
     * So, we need a way to fix it.
     *
     * ### Solution
     * Thanks to Paper API, we can just listen to this particular event and modify its states!
     * We fix the issue by simply changing the target world to the one defined in the plugin config.
     * With that, the Nether Portals in custom worlds can work correctly, just like those in default worlds.
     * See [#5619](https://github.com/PaperMC/Paper/pull/5619) for more information.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPortalReady(e: EntityPortalReadyEvent) {
        val world = e.entity.world

        if (isDefaultWorld(world)) {
            logger.info("Portal links for `${world.name}` is handled by vanilla game")
            return
        }

        val target = nameLinks.findTo(world, e.portalType)
        if (target != null) {
            e.targetWorld = target // See javadoc of `e.targetWorld` for motivation
            logger.info("Redirect portal link: `${world.name}` -> `${target.name}`")
        } else {
            logger.warn("Cannot find portal link: `${world.name}` -> `null`")
        }
    }

    // TODO handle the case where End Portals in custom end world
    //  should direct to the custom normal world. Currently, it directs
    //  to the default normal world.

    /**
     * (For Players) This handler:
     * - modifies location scale for Nether Portals.
     * - modifies target world for End Portals.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerPortal(e: PlayerPortalEvent) {
        when (e.cause) {
            // This handles both ways: Normal <-> The_Nether
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL -> {
                val to = e.to
                val from = e.from
                val newTo = findNetherPortalTeleportLocation(from = from, target = to, entity = e.player)
                newTo?.let { e.to = it }
            }

            // This handles both ways: Normal <-> The_End
            PlayerTeleportEvent.TeleportCause.END_PORTAL -> {
                val fromLocation = e.from
                val newTo = findEndPortalTeleportLocation(from = fromLocation.world)
                newTo?.let { e.to = it }
            }

            else -> {}
        }
    }

    /**
     * (For Non-Player Entities) This handler:
     * - modifies location scale for Nether Portals.
     * - modifies target world for End Portals.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityPortal(e: EntityPortalEvent) {
        when (e.portalType) {
            // This handles both ways: Normal <-> The_Nether
            PortalType.NETHER -> {
                val toLocation = e.to
                val fromLocation = e.from
                val newTo = findNetherPortalTeleportLocation(from = fromLocation, target = toLocation, entity = e.entity)
                e.to = newTo
            }

            // This handles both ways: Normal <-> The_End
            PortalType.ENDER -> {
                val fromLocation = e.from
                val newTo = findEndPortalTeleportLocation(from = fromLocation.world)
                e.to = newTo
            }

            else -> {}
        }
    }

    private fun isDefaultWorld(world: World): Boolean {
        // So far, default worlds always have fixed NamespacedKey values
        // This is better than directly reading the server.properties
        return world.key.let { it == OVERWORLD_KEY || it == THE_NETHER_KEY || it == THE_END_KEY }
    }

    private fun getWorldScaling(world: World): Double {
        return when (world.environment) {
            Environment.NORMAL -> plugin.settings.normalScale
            Environment.NETHER -> plugin.settings.netherScale
            Environment.THE_END -> plugin.settings.endScale
            else -> error(world.name + " has no scale defined in the config")
        }
    }

    /**
     * Finds the teleport location for Nether Portals, under custom location scale.
     *
     * To find the teleport location for End Portals, use [findEndPortalTeleportLocation].
     */
    private fun findNetherPortalTeleportLocation(from: Location, target: Location?, entity: Entity?): Location? {
        if (target == null) return null

        // Compute the scaled target location (only X and Z)
        val fromScaling = getWorldScaling(from.getWorld())
        val toScaling = getWorldScaling(target.getWorld())
        val scaling = fromScaling / toScaling
        val scaleX = from.x * scaling
        val scaleZ = from.z * scaling

        if (entity is Player) {
            plugin.languages.of("world_scale_tips")
                .resolver(
                    Formatter.number("scale_from", fromScaling),
                    Formatter.number("scale_to", toScaling)
                ).send(entity)
        }

        return Location(
            target.getWorld(),
            scaleX,
            from.y,
            scaleZ,
            from.yaw,
            from.pitch
        )
    }

    /**
     * Finds the teleport location for End Portals.
     */
    private fun findEndPortalTeleportLocation(from: World): Location? {
        val to = nameLinks.findTo(from, PortalType.ENDER) ?: return null

        when (from.environment) {
            // Teleport direction: Normal -> The_end
            Environment.NORMAL -> {
                if (to.environment != Environment.THE_END) {
                    error("Target world environment is not ${Environment.THE_END}")
                }
                val toLoc = Location(to, 100.5, 50.0, 0.5) // This is the vanilla location for obsidian platform
                // Construct the vanilla obsidian platform
                val toBlock = toLoc.block
                for (x in toBlock.x - 2..toBlock.x + 2) {
                    for (z in toBlock.z - 2..toBlock.z + 2) {
                        val platformBlock = toLoc.getWorld().getBlockAt(x, toBlock.y - 1, z)
                        if (platformBlock.type != Material.OBSIDIAN) platformBlock.type = Material.OBSIDIAN
                        for (yMod in 1..3) {
                            val b = platformBlock.getRelative(BlockFace.UP, yMod)
                            if (b.type != Material.AIR) b.type = Material.AIR
                        }
                    }
                }
                return toLoc
            }

            // Teleport direction: The_end -> Normal
            Environment.THE_END -> {
                if (to.environment != Environment.NORMAL) {
                    error("Target world environment is not ${Environment.NORMAL}")
                }
                return to.spawnLocation
            }

            else -> error("The environment of `from` world is neither ${Environment.NORMAL} nor ${Environment.THE_END}")
        }
    }
}