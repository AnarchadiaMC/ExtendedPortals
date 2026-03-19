package org.anarchadia.extendedportals;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.EndGateway;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Generates and maintains the fixed overworld portal network.
 */
public final class Loader extends JavaPlugin implements Listener {

    private static final String OVERWORLD_NAME = "world";
    private static final String END_WORLD_NAME = "world_the_end";
    private static final int PORTAL_SIZE = 5;
    private static final int PORTAL_OFFSET_IN_CHUNK = 7;
    private static final int PORTAL_CENTER_OFFSET = PORTAL_SIZE / 2;
    private static final int PORTAL_CLEARANCE_HEIGHT = 3;
    private static final int GATEWAY_Y_OFFSET = 10;
    private static final int GATEWAY_SHELL_OFFSET = 2;
    private static final int END_PLATFORM_RADIUS = 2;
    private static final int END_CLEARANCE_HEIGHT = 4;
    private static final double MAX_TELEPORT_VELOCITY = 200.0D;
    private static final long TELEPORT_COOLDOWN_MS = 1500L;
    private static final byte GATEWAY_MARKER_VALUE = 1;

    private final Set<Integer> portalChunks = Collections.unmodifiableSet(new HashSet<Integer>(Arrays.asList(
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
    )));
    private final Map<Long, Location> loadedPortalGateways = new HashMap<Long, Location>();
    private final Map<UUID, Long> teleportCooldowns = new HashMap<UUID, Long>();

    private NamespacedKey gatewayMarkerKey;
    private long nextCooldownCleanupAt;
    private boolean warnedAboutMissingEndWorld;

    @Override
    public void onEnable() {
        this.gatewayMarkerKey = new NamespacedKey(this, "managed_gateway");

        getServer().getPluginManager().registerEvents(this, this);
        trackLoadedPortalChunks();
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                pollLoadedPortalGateways();
            }
        }, 1L, 1L);

        if (Bukkit.getWorld(END_WORLD_NAME) == null) {
            warnMissingEndWorld();
        }
    }

    @Override
    public void onDisable() {
        loadedPortalGateways.clear();
        teleportCooldowns.clear();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isManagedOverworld(event.getWorld())) {
            return;
        }

        Chunk chunk = event.getChunk();
        if (!isPortalChunk(chunk.getX(), chunk.getZ())) {
            return;
        }

        loadedPortalGateways.put(Long.valueOf(chunkKey(chunk.getX(), chunk.getZ())), ensurePortal(chunk));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        loadedPortalGateways.remove(Long.valueOf(chunkKey(event.getChunk().getX(), event.getChunk().getZ())));
    }

    private void trackLoadedPortalChunks() {
        for (World world : Bukkit.getWorlds()) {
            if (!isManagedOverworld(world)) {
                continue;
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                if (!isPortalChunk(chunk.getX(), chunk.getZ())) {
                    continue;
                }

                loadedPortalGateways.put(Long.valueOf(chunkKey(chunk.getX(), chunk.getZ())), ensurePortal(chunk));
            }
        }
    }

    private Location ensurePortal(Chunk chunk) {
        World world = chunk.getWorld();
        int startX = getPortalStart(chunk.getX());
        int startZ = getPortalStart(chunk.getZ());

        List<Integer> existingGatewayBases = findGatewayBases(world, startX, startZ);
        if (!existingGatewayBases.isEmpty()) {
            int canonicalBaseY = existingGatewayBases.get(0);

            for (int index = 1; index < existingGatewayBases.size(); index++) {
                clearPortalStructure(world, startX, existingGatewayBases.get(index), startZ);
            }

            if (!isPortalStructureComplete(world, startX, canonicalBaseY, startZ)) {
                buildPortalStructure(world, startX, canonicalBaseY, startZ);
            } else {
                createGatewayStructure(world, startX + PORTAL_CENTER_OFFSET, canonicalBaseY + GATEWAY_Y_OFFSET, startZ + PORTAL_CENTER_OFFSET);
            }

            return createGatewayLocation(world, startX, canonicalBaseY, startZ);
        }

        Integer recoveredBaseY = findPortalBaseFromStructure(world, startX, startZ);
        int baseY = recoveredBaseY != null ? recoveredBaseY.intValue() : resolvePortalBaseY(world, startX, startZ);
        buildPortalStructure(world, startX, baseY, startZ);
        return createGatewayLocation(world, startX, baseY, startZ);
    }

    private List<Integer> findGatewayBases(World world, int startX, int startZ) {
        int centerX = startX + PORTAL_CENTER_OFFSET;
        int centerZ = startZ + PORTAL_CENTER_OFFSET;
        List<Integer> baseHeights = new ArrayList<Integer>();

        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            if (world.getBlockAt(centerX, y, centerZ).getType() == Material.END_GATEWAY) {
                int baseY = y - GATEWAY_Y_OFFSET;
                if (isWithinBuildHeight(world, baseY)) {
                    baseHeights.add(Integer.valueOf(baseY));
                }
            }
        }

        return baseHeights;
    }

    private Integer findPortalBaseFromStructure(World world, int startX, int startZ) {
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            if (hasPortalSignatureAt(world, startX, y, startZ)) {
                return Integer.valueOf(y);
            }
        }

        return null;
    }

    private boolean hasPortalSignatureAt(World world, int startX, int baseY, int startZ) {
        int portalBlockCount = 0;

        for (int x = startX; x < startX + PORTAL_SIZE; x++) {
            for (int z = startZ; z < startZ + PORTAL_SIZE; z++) {
                Material material = world.getBlockAt(x, baseY, z).getType();
                if (material == Material.END_PORTAL || material == Material.END_PORTAL_FRAME) {
                    portalBlockCount++;
                }
            }
        }

        return portalBlockCount >= 4;
    }

    private int resolvePortalBaseY(World world, int startX, int startZ) {
        int centerX = startX + PORTAL_CENTER_OFFSET;
        int centerZ = startZ + PORTAL_CENTER_OFFSET;
        int highestY = world.getHighestBlockAt(centerX, centerZ, HeightMap.MOTION_BLOCKING_NO_LEAVES).getY();

        for (int y = highestY; y >= world.getMinHeight(); y--) {
            Material material = world.getBlockAt(centerX, y, centerZ).getType();
            if (material == Material.AIR || isManagedStructureMaterial(material)) {
                continue;
            }

            return clampBaseY(world, y);
        }

        return clampBaseY(world, highestY);
    }

    private int clampBaseY(World world, int baseY) {
        int minBaseY = world.getMinHeight();
        int maxBaseY = world.getMaxHeight() - GATEWAY_Y_OFFSET - GATEWAY_SHELL_OFFSET - 1;
        return Math.max(minBaseY, Math.min(baseY, maxBaseY));
    }

    private boolean isPortalStructureComplete(World world, int startX, int baseY, int startZ) {
        if (!isWithinBuildHeight(world, baseY) || !isWithinBuildHeight(world, baseY + GATEWAY_Y_OFFSET + GATEWAY_SHELL_OFFSET)) {
            return false;
        }

        for (int x = startX; x < startX + PORTAL_SIZE; x++) {
            boolean isXBoundary = x == startX || x == startX + PORTAL_SIZE - 1;
            for (int z = startZ; z < startZ + PORTAL_SIZE; z++) {
                boolean isZBoundary = z == startZ || z == startZ + PORTAL_SIZE - 1;
                if (isXBoundary && isZBoundary) {
                    continue;
                }

                Material expected = isXBoundary || isZBoundary ? Material.END_PORTAL_FRAME : Material.END_PORTAL;
                if (world.getBlockAt(x, baseY, z).getType() != expected) {
                    return false;
                }
            }
        }

        int centerX = startX + PORTAL_CENTER_OFFSET;
        int centerZ = startZ + PORTAL_CENTER_OFFSET;
        int gatewayY = baseY + GATEWAY_Y_OFFSET;
        if (world.getBlockAt(centerX, gatewayY, centerZ).getType() != Material.END_GATEWAY) {
            return false;
        }

        if (world.getBlockAt(centerX, gatewayY + GATEWAY_SHELL_OFFSET, centerZ).getType() != Material.BEDROCK) {
            return false;
        }

        if (world.getBlockAt(centerX, gatewayY - GATEWAY_SHELL_OFFSET, centerZ).getType() != Material.BEDROCK) {
            return false;
        }

        return isBedrock(world, centerX - 1, gatewayY + 1, centerZ)
                && isBedrock(world, centerX + 1, gatewayY + 1, centerZ)
                && isBedrock(world, centerX, gatewayY + 1, centerZ - 1)
                && isBedrock(world, centerX, gatewayY + 1, centerZ + 1)
                && isBedrock(world, centerX - 1, gatewayY - 1, centerZ)
                && isBedrock(world, centerX + 1, gatewayY - 1, centerZ)
                && isBedrock(world, centerX, gatewayY - 1, centerZ - 1)
                && isBedrock(world, centerX, gatewayY - 1, centerZ + 1);
    }

    private boolean isBedrock(World world, int x, int y, int z) {
        return isWithinBuildHeight(world, y) && world.getBlockAt(x, y, z).getType() == Material.BEDROCK;
    }

    private void buildPortalStructure(World world, int startX, int baseY, int startZ) {
        int clampedBaseY = clampBaseY(world, baseY);

        for (int x = startX; x < startX + PORTAL_SIZE; x++) {
            boolean isXBoundary = x == startX || x == startX + PORTAL_SIZE - 1;
            for (int z = startZ; z < startZ + PORTAL_SIZE; z++) {
                boolean isZBoundary = z == startZ || z == startZ + PORTAL_SIZE - 1;
                boolean isCorner = isXBoundary && isZBoundary;
                boolean isFrame = isXBoundary || isZBoundary;

                Block baseBlock = world.getBlockAt(x, clampedBaseY, z);
                if (isCorner) {
                    setBlock(baseBlock, Material.AIR);
                } else if (isFrame) {
                    setBlock(baseBlock, Material.END_PORTAL_FRAME);
                    orientPortalFrame(baseBlock, x, startX, z, startZ);
                } else {
                    setBlock(baseBlock, Material.END_PORTAL);
                }

                for (int y = 1; y <= PORTAL_CLEARANCE_HEIGHT; y++) {
                    clearToAir(world.getBlockAt(x, clampedBaseY + y, z));
                }
            }
        }

        createGatewayStructure(world, startX + PORTAL_CENTER_OFFSET, clampedBaseY + GATEWAY_Y_OFFSET, startZ + PORTAL_CENTER_OFFSET);
    }

    private void createGatewayStructure(World world, int centerX, int centerY, int centerZ) {
        clearToAir(world.getBlockAt(centerX, centerY - 1, centerZ));
        clearToAir(world.getBlockAt(centerX, centerY + 1, centerZ));

        setBlock(world.getBlockAt(centerX, centerY + GATEWAY_SHELL_OFFSET, centerZ), Material.BEDROCK);
        setBlock(world.getBlockAt(centerX, centerY - GATEWAY_SHELL_OFFSET, centerZ), Material.BEDROCK);

        for (int offset = -1; offset <= 1; offset += 2) {
            setBlock(world.getBlockAt(centerX + offset, centerY + 1, centerZ), Material.BEDROCK);
            setBlock(world.getBlockAt(centerX, centerY + 1, centerZ + offset), Material.BEDROCK);
            setBlock(world.getBlockAt(centerX + offset, centerY - 1, centerZ), Material.BEDROCK);
            setBlock(world.getBlockAt(centerX, centerY - 1, centerZ + offset), Material.BEDROCK);
        }

        Block gatewayBlock = world.getBlockAt(centerX, centerY, centerZ);
        setBlock(gatewayBlock, Material.END_GATEWAY);
        configureGatewayState(gatewayBlock);
    }

    private void configureGatewayState(Block gatewayBlock) {
        BlockState state = gatewayBlock.getState();
        if (!(state instanceof EndGateway)) {
            return;
        }

        EndGateway gateway = (EndGateway) state;
        World endWorld = Bukkit.getWorld(END_WORLD_NAME);
        if (endWorld != null) {
            gateway.setExitLocation(new Location(
                    endWorld,
                    gatewayBlock.getX() + 0.5D,
                    gatewayBlock.getY(),
                    gatewayBlock.getZ() + 0.5D
            ));
            gateway.setExactTeleport(true);
        }

        gateway.getPersistentDataContainer().set(gatewayMarkerKey, PersistentDataType.BYTE, Byte.valueOf(GATEWAY_MARKER_VALUE));
        gateway.update(true, false);
    }

    private void clearPortalStructure(World world, int startX, int baseY, int startZ) {
        for (int x = startX; x < startX + PORTAL_SIZE; x++) {
            for (int z = startZ; z < startZ + PORTAL_SIZE; z++) {
                for (int y = 0; y <= PORTAL_CLEARANCE_HEIGHT; y++) {
                    clearManagedStructure(world.getBlockAt(x, baseY + y, z));
                }
            }
        }

        int centerX = startX + PORTAL_CENTER_OFFSET;
        int centerZ = startZ + PORTAL_CENTER_OFFSET;
        int gatewayY = baseY + GATEWAY_Y_OFFSET;

        clearManagedStructure(world.getBlockAt(centerX, gatewayY, centerZ));
        clearManagedStructure(world.getBlockAt(centerX, gatewayY + 1, centerZ));
        clearManagedStructure(world.getBlockAt(centerX, gatewayY - 1, centerZ));
        clearManagedStructure(world.getBlockAt(centerX, gatewayY + GATEWAY_SHELL_OFFSET, centerZ));
        clearManagedStructure(world.getBlockAt(centerX, gatewayY - GATEWAY_SHELL_OFFSET, centerZ));

        for (int offset = -1; offset <= 1; offset += 2) {
            clearManagedStructure(world.getBlockAt(centerX + offset, gatewayY + 1, centerZ));
            clearManagedStructure(world.getBlockAt(centerX, gatewayY + 1, centerZ + offset));
            clearManagedStructure(world.getBlockAt(centerX + offset, gatewayY - 1, centerZ));
            clearManagedStructure(world.getBlockAt(centerX, gatewayY - 1, centerZ + offset));
        }
    }

    private void clearManagedStructure(Block block) {
        if (isManagedStructureMaterial(block.getType())) {
            clearToAir(block);
        }
    }

    private boolean isManagedStructureMaterial(Material material) {
        return material == Material.END_PORTAL
                || material == Material.END_PORTAL_FRAME
                || material == Material.END_GATEWAY
                || material == Material.BEDROCK;
    }

    private void orientPortalFrame(Block block, int x, int startX, int z, int startZ) {
        Directional directional = (Directional) block.getBlockData();
        if (x == startX) {
            directional.setFacing(BlockFace.EAST);
        } else if (x == startX + PORTAL_SIZE - 1) {
            directional.setFacing(BlockFace.WEST);
        } else if (z == startZ) {
            directional.setFacing(BlockFace.SOUTH);
        } else if (z == startZ + PORTAL_SIZE - 1) {
            directional.setFacing(BlockFace.NORTH);
        }

        EndPortalFrame endPortalFrame = (EndPortalFrame) directional;
        endPortalFrame.setEye(true);
        block.setBlockData(endPortalFrame, false);
    }

    private boolean isManagedGateway(Block block) {
        if (block.getType() != Material.END_GATEWAY || !isManagedOverworld(block.getWorld())) {
            return false;
        }

        Chunk chunk = block.getChunk();
        if (!isPortalChunk(chunk.getX(), chunk.getZ())) {
            return false;
        }

        int expectedCenterX = getPortalStart(chunk.getX()) + PORTAL_CENTER_OFFSET;
        int expectedCenterZ = getPortalStart(chunk.getZ()) + PORTAL_CENTER_OFFSET;
        if (block.getX() != expectedCenterX || block.getZ() != expectedCenterZ) {
            return false;
        }

        BlockState state = block.getState();
        if (state instanceof EndGateway) {
            Byte marker = ((EndGateway) state).getPersistentDataContainer().get(gatewayMarkerKey, PersistentDataType.BYTE);
            return marker == null || marker.byteValue() == GATEWAY_MARKER_VALUE;
        }

        return true;
    }

    private void pollLoadedPortalGateways() {
        if (loadedPortalGateways.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= nextCooldownCleanupAt) {
            cleanupExpiredCooldowns(now);
            nextCooldownCleanupAt = now + TELEPORT_COOLDOWN_MS;
        }

        for (Location gatewayLocation : loadedPortalGateways.values()) {
            World world = gatewayLocation.getWorld();
            if (world == null || !world.isChunkLoaded(gatewayLocation.getBlockX() >> 4, gatewayLocation.getBlockZ() >> 4)) {
                continue;
            }

            Block gatewayBlock = world.getBlockAt(gatewayLocation);
            if (!isManagedGateway(gatewayBlock)) {
                continue;
            }

            for (Entity entity : gatewayBlock.getChunk().getEntities()) {
                if (!shouldTeleportEntity(entity, gatewayBlock)) {
                    continue;
                }

                teleportEntityThroughGateway(entity, gatewayBlock);
            }
        }
    }

    private boolean shouldTeleportEntity(Entity entity, Block gatewayBlock) {
        if (!entity.isValid() || entity.isDead()) {
            return false;
        }

        if (entity.isInsideVehicle() || !entity.getPassengers().isEmpty()) {
            return false;
        }

        Location entityLocation = entity.getLocation();
        return entityLocation.getBlockX() == gatewayBlock.getX()
                && entityLocation.getBlockY() == gatewayBlock.getY()
                && entityLocation.getBlockZ() == gatewayBlock.getZ();
    }

    private void teleportEntityThroughGateway(Entity entity, Block gatewayBlock) {
        if (!beginTeleportCooldown(entity.getUniqueId())) {
            return;
        }

        World endWorld = Bukkit.getWorld(END_WORLD_NAME);
        if (endWorld == null) {
            teleportCooldowns.remove(entity.getUniqueId());
            warnMissingEndWorld();
            return;
        }

        Vector velocity = capVelocity(entity.getVelocity());
        Location origin = entity.getLocation();
        Location destination = new Location(
                endWorld,
                gatewayBlock.getX() + 0.5D,
                gatewayBlock.getY(),
                gatewayBlock.getZ() + 0.5D,
                origin.getYaw(),
                origin.getPitch()
        );

        prepareEndArrival(destination);

        if (!entity.teleport(destination, PlayerTeleportEvent.TeleportCause.END_GATEWAY)) {
            teleportCooldowns.remove(entity.getUniqueId());
            return;
        }

        if (velocity.lengthSquared() == 0.0D) {
            return;
        }

        final Vector preservedVelocity = velocity;
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (entity.isValid()) {
                    entity.setVelocity(preservedVelocity);
                }
            }
        });
    }

    private boolean beginTeleportCooldown(UUID entityId) {
        long now = System.currentTimeMillis();
        if (now >= nextCooldownCleanupAt) {
            cleanupExpiredCooldowns(now);
            nextCooldownCleanupAt = now + TELEPORT_COOLDOWN_MS;
        }

        Long expiresAt = teleportCooldowns.get(entityId);
        if (expiresAt != null && expiresAt.longValue() > now) {
            return false;
        }

        teleportCooldowns.put(entityId, Long.valueOf(now + TELEPORT_COOLDOWN_MS));
        return true;
    }

    private void cleanupExpiredCooldowns(long now) {
        List<UUID> expiredEntries = new ArrayList<UUID>();
        for (Map.Entry<UUID, Long> entry : teleportCooldowns.entrySet()) {
            if (entry.getValue().longValue() <= now) {
                expiredEntries.add(entry.getKey());
            }
        }

        for (UUID entityId : expiredEntries) {
            teleportCooldowns.remove(entityId);
        }
    }

    private Vector capVelocity(Vector velocity) {
        Vector safeVelocity = velocity.clone();
        double maxVelocitySquared = MAX_TELEPORT_VELOCITY * MAX_TELEPORT_VELOCITY;

        if (safeVelocity.lengthSquared() > maxVelocitySquared) {
            safeVelocity.normalize().multiply(MAX_TELEPORT_VELOCITY);
        }

        return safeVelocity;
    }

    private void prepareEndArrival(Location destination) {
        World world = destination.getWorld();
        if (world == null) {
            return;
        }

        int baseX = destination.getBlockX();
        int baseY = destination.getBlockY();
        int baseZ = destination.getBlockZ();

        for (int x = -END_PLATFORM_RADIUS; x <= END_PLATFORM_RADIUS; x++) {
            for (int z = -END_PLATFORM_RADIUS; z <= END_PLATFORM_RADIUS; z++) {
                setBlock(world.getBlockAt(baseX + x, baseY - 1, baseZ + z), Material.OBSIDIAN);

                for (int y = 0; y < END_CLEARANCE_HEIGHT; y++) {
                    clearToAir(world.getBlockAt(baseX + x, baseY + y, baseZ + z));
                }
            }
        }
    }

    private void setBlock(Block block, Material material) {
        if (block.getType() != material) {
            block.setType(material, false);
        }
    }

    private void clearToAir(Block block) {
        if (block.getType() != Material.AIR) {
            block.setType(Material.AIR, false);
        }
    }

    private boolean isManagedOverworld(World world) {
        return world.getName().equalsIgnoreCase(OVERWORLD_NAME);
    }

    private boolean isPortalChunk(int chunkX, int chunkZ) {
        return portalChunks.contains(Integer.valueOf(Math.abs(chunkX)))
                && portalChunks.contains(Integer.valueOf(Math.abs(chunkZ)));
    }

    private int getPortalStart(int chunkCoordinate) {
        return (chunkCoordinate << 4) + PORTAL_OFFSET_IN_CHUNK;
    }

    private Location createGatewayLocation(World world, int startX, int baseY, int startZ) {
        return new Location(
                world,
                startX + PORTAL_CENTER_OFFSET,
                baseY + GATEWAY_Y_OFFSET,
                startZ + PORTAL_CENTER_OFFSET
        );
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private boolean isWithinBuildHeight(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private void warnMissingEndWorld() {
        if (warnedAboutMissingEndWorld) {
            return;
        }

        warnedAboutMissingEndWorld = true;
        getLogger().warning("End world '" + END_WORLD_NAME + "' is not loaded. Extended gateway teleports will stay disabled until that world is available.");
    }
}
