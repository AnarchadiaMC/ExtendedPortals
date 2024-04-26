package org.anarchadia.extendedportals;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The main class for handling the creation of custom portals
 * in the Minecraft world.
 */
public final class Loader extends JavaPlugin implements Listener {

    private final Set<Integer> portalChunks = new HashSet<>(Arrays.asList(
            156,     // Coordinates: 2500
            312,     // Coordinates: 5000
            625,     // Coordinates: 10000
            1250,    // Coordinates: 20000
            1875,    // Coordinates: 30000
            3125,    // Coordinates: 50000
            6250,    // Coordinates: 100000
            12500,   // Coordinates: 200000
            31250,   // Coordinates: 500000
            62500,   // Coordinates: 1000000
            125000,  // Coordinates: 2000000
            250000,  // Coordinates: 4000000
            312500,  // Coordinates: 5000000
            625000,  // Coordinates: 10000000
            937500,  // Coordinates: 15000000
            1250000, // Coordinates: 20000000
            1562500, // Coordinates: 25000000
            1812500, // Coordinates: 29000000
            1843750, // Coordinates: 29500000
            1874995  // Coordinates: 29999920
    ));

    /**
     * Called when the plugin is enabled.
     */
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * Called when the plugin is disabled.
     */
    @Override
    public void onDisable() {
    }

    /**
     * Responds to the chunk load event, checks if the chunk should contain a custom portal,
     * and if so, attempts to spawn it.
     *
     * @param event The chunk load event.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onChunkLoad(ChunkLoadEvent event) {
        String worldName = event.getWorld().getName();
        Chunk chunk = event.getChunk();
        if (!worldName.equalsIgnoreCase("world")) return;

        if (portalChunks.contains(Math.abs(chunk.getX())) && portalChunks.contains(Math.abs(chunk.getZ()))) {
            int startX = (chunk.getX() << 4);
            int startZ = (chunk.getZ() << 4);

            int groundY = chunk.getWorld().getHighestBlockYAt(startX + 8, startZ + 8);

            spawnPortal(chunk, startX + 7, groundY, startZ + 7);
        }
    }

    /**
     * Spawns a custom portal in the specified location if one does not already exist.
     *
     * @param chunk   The chunk where the portal is to be spawned.
     * @param startX  The starting X coordinate for the portal.
     * @param groundY The ground Y coordinate where the portal is to start.
     * @param startZ  The starting Z coordinate for the portal.
     */
    private void spawnPortal(Chunk chunk, int startX, int groundY, int startZ) {
        if (portalExists(chunk, startX, groundY, startZ)) {
            return;
        }

        World world = chunk.getWorld();

        for (int x = startX; x < startX + 5; x++) {
            boolean isXBound = x == startX || x == startX + 4;
            for (int z = startZ; z < startZ + 5; z++) {
                boolean isZBound = z == startZ || z == startZ + 4;

                boolean isFrame = isXBound || isZBound;
                boolean isCorner = isXBound && isZBound;

                Material blockType = isCorner ? null : (isFrame ? Material.END_PORTAL_FRAME : Material.END_PORTAL);

                for (int y = groundY; y <= groundY + 3; y++) {
                    if (y > groundY && blockType == Material.END_PORTAL)
                        continue;

                    Block block = world.getBlockAt(x, y, z);

                    if (y == groundY && blockType != null) {
                        block.setType(blockType);
                        if (blockType == Material.END_PORTAL_FRAME) {
                            orientPortalFrame(block, x, startX, z, startZ);

                            EndPortalFrame endPortalFrame = (EndPortalFrame) block.getBlockData();
                            endPortalFrame.setEye(true);
                            block.setBlockData(endPortalFrame);
                        }
                    } else {
                        block.setType(Material.AIR);
                    }
                }
            }
        }

        Block exitGatewayCenter = world.getBlockAt(startX + 2, groundY + 10, startZ + 2);
        createExitGateway(exitGatewayCenter);
    }

    /**
     * Creates an exit gateway block with surrounding bedrock structure.
     *
     * @param centerBlock The center block location to create the gateway.
     */
    private void createExitGateway(Block centerBlock) {
        centerBlock.setType(Material.END_GATEWAY);

        Block above = centerBlock.getRelative(BlockFace.UP);
        Block below = centerBlock.getRelative(BlockFace.DOWN);

        above.getRelative(BlockFace.UP).setType(Material.BEDROCK);
        below.getRelative(BlockFace.DOWN).setType(Material.BEDROCK);

        for (int i = -1; i <= 1; i++) {
            if (i == 0) continue;

            above.getRelative(i, 0, 0).setType(Material.BEDROCK);
            above.getRelative(0, 0, i).setType(Material.BEDROCK);
            below.getRelative(i, 0, 0).setType(Material.BEDROCK);
            below.getRelative(0, 0, i).setType(Material.BEDROCK);
        }
    }

    /**
     * Orientates the portal frame based on its position relative to the portal structure.
     *
     * @param block   The block to orient.
     * @param x       The x coordinate of the block to orient.
     * @param startX  The starting x coordinate of the portal.
     * @param z       The z coordinate of the block to orient.
     * @param startZ  The starting z coordinate of the portal.
     */
    private void orientPortalFrame(Block block, int x, int startX, int z, int startZ) {
        if (block.getType() != Material.END_PORTAL_FRAME) return;

        Directional directional = (Directional) block.getBlockData();
        if (x == startX) directional.setFacing(BlockFace.EAST);
        else if (x == startX + 4) directional.setFacing(BlockFace.WEST);
        else if (z == startZ) directional.setFacing(BlockFace.SOUTH);
        else if (z == startZ + 4) directional.setFacing(BlockFace.NORTH);

        block.setBlockData(directional);
    }

    /**
     * Checks if a portal already exists within a certain vicinity of the specified location.
     *
     * @param chunk   The chunk to check within.
     * @param startX  The starting x coordinate to check from.
     * @param groundY The ground y coordinate to check from.
     * @param startZ  The starting z coordinate to check from.
     * @return true if a portal exists, false otherwise.
     */
    private boolean portalExists(Chunk chunk, int startX, int groundY, int startZ) {
        World world = chunk.getWorld();

        int maxX = startX + 5;
        int maxZ = startZ + 5;
        int maxHeightCheck = groundY + 11;

        for (int x = startX - 5; x <= maxX; x++) {
            for (int y = groundY - 1; y <= maxHeightCheck; y++) {
                for (int z = startZ - 5; z <= maxZ; z++) {
                    Material blockType = world.getBlockAt(x, y, z).getType();

                    if (blockType == Material.END_PORTAL_FRAME || blockType == Material.END_PORTAL) {
                        return true;
                    }

                    if (blockType == Material.END_GATEWAY) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Responds to the player movement event, checks if the player has moved into position
     * to be teleported through a portal, and performs the teleportation if conditions are met.
     *
     * @param event The player movement event.
     */
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();

        int chunkX = to.getBlockX() >> 4;
        int chunkZ = to.getBlockZ() >> 4;

        if (!portalChunks.contains(Math.abs(chunkX)) || !portalChunks.contains(Math.abs(chunkZ))) {
            return;
        }

        Block block = to.getWorld().getBlockAt(to.getBlockX(), to.getBlockY(), to.getBlockZ());

        if (block.getType() == Material.END_GATEWAY) {
            Player player = event.getPlayer();
            World world = player.getWorld();

            if (!world.getName().equalsIgnoreCase("world")) return;

            Location endGatewayLocation = new Location(Bukkit.getWorld("world_the_end"), block.getX(), block.getY(), block.getZ());
            player.teleport(endGatewayLocation);
        }
    }
}