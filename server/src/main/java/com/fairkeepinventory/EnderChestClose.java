package com.fairkeepinventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.Material;

import com.fairkeepinventory.model.InventoryId;
import com.fairkeepinventory.model.OwnershipTable;

public class EnderChestClose implements Listener {
    protected OwnershipTable table = OwnershipTable.getInstance();
    protected Map<Location, Map<UUID, Instant>> playerFirstOpenTime = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        final Inventory inventory = event.getInventory();
        if (this.isEnderChest(inventory)) {
            Location location = inventory.getLocation();
            if (location != null && Duration.between(
                    playerFirstOpenTime.get(location).get(event.getPlayer().getUniqueId()),
                    Instant.now())
                .minusSeconds(600)
                .isPositive()) {
                table.tickInventory(600, inv -> inv.getInventory().equals(inventory));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        final Inventory inventory = event.getInventory();
        if (this.isEnderChest(inventory)) {
            Location location = inventory.getLocation();
            if (location != null) {
                playerFirstOpenTime
                    .computeIfAbsent(location, k -> new HashMap<>())
                    .putIfAbsent(event.getPlayer().getUniqueId(), Instant.now());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getState().getType() == Material.ENDER_CHEST) {
            playerFirstOpenTime.remove(event.getBlock().getLocation());
        }
    }

    protected boolean isEnderChest(Inventory inventory) {
        return InventoryId.from(inventory) instanceof InventoryId.PlayerInventoryId playerInventory &&
                playerInventory.getInventoryType() == InventoryType.ENDER_CHEST;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        boolean firstLocation = true;

        for (Map.Entry<Location, Map<UUID, Instant>> locEntry : playerFirstOpenTime.entrySet()) {
            Location loc = locEntry.getKey();
            Map<UUID, Instant> playerMap = locEntry.getValue();
            if (loc == null || loc.getWorld() == null || playerMap == null || playerMap.isEmpty()) {
                continue;
            }

            if (!firstLocation) {
                sb.append(";");
            }
            firstLocation = false;

            // Location part: worldUuid,x,y,z:
            sb.append(loc.getWorld().getUID()).append(",")
              .append(loc.getBlockX()).append(",")
              .append(loc.getBlockY()).append(",")
              .append(loc.getBlockZ()).append(":");

            // Players part: uuid@epochMilli,uuid2@epochMilli2
            boolean firstPlayer = true;
            for (Map.Entry<UUID, Instant> playerEntry : playerMap.entrySet()) {
                if (!firstPlayer) {
                    sb.append(",");
                }
                firstPlayer = false;
                UUID uuid = playerEntry.getKey();
                Instant instant = playerEntry.getValue();
                sb.append(uuid).append("@").append(instant.toEpochMilli());
            }
        }

        return sb.toString();
    }

    public void deserialize(String data) {
        playerFirstOpenTime.clear();
        if (data == null || data.isEmpty()) {
            return;
        }

        String[] locationChunks = data.split(";");
        for (String chunk : locationChunks) {
            if (chunk.isEmpty()) continue;

            // chunk: worldUuid,x,y,z:uuid@epochMilli,uuid2@epochMilli2
            String[] locAndPlayers = chunk.split(":");
            if (locAndPlayers.length == 0) continue;

            String[] locParts = locAndPlayers[0].split(",");
            if (locParts.length != 4) continue;

            UUID worldId = UUID.fromString(locParts[0]);
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldId);
            if (world == null) {
                // Skip entries for worlds that no longer exist / are not loaded.
                continue;
            }

            int x = Integer.parseInt(locParts[1]);
            int y = Integer.parseInt(locParts[2]);
            int z = Integer.parseInt(locParts[3]);

            Location loc = new Location(world, x, y, z);
            Map<UUID, Instant> playerMap = new HashMap<>();

            if (locAndPlayers.length > 1 && !locAndPlayers[1].isEmpty()) {
                String[] playerParts = locAndPlayers[1].split(",");
                for (String p : playerParts) {
                    if (p.isEmpty()) continue;
                    String[] userAndTime = p.split("@");
                    if (userAndTime.length != 2) continue;

                    UUID uuid = UUID.fromString(userAndTime[0]);
                    long epochMilli = Long.parseLong(userAndTime[1]);
                    playerMap.put(uuid, Instant.ofEpochMilli(epochMilli));
                }
            }

            if (!playerMap.isEmpty()) {
                playerFirstOpenTime.put(loc, playerMap);
            }
        }
    }

    /**
     * Persist current state of playerFirstOpenTime into SQLite.
     *
     * This method is blocking. Run it off the main server thread.
     */
    public void persist(Connection connection) throws SQLException {
        // 1) Ensure table exists
        try (PreparedStatement ps = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS enderchest_state (" +
                "  id   INTEGER PRIMARY KEY CHECK (id = 1)," +
                "  data TEXT NOT NULL" +
                ")"
        )) {
            ps.executeUpdate();
        }

        // 2) Serialize current state
        String data = serialize();

        // 3) Upsert single row with id = 1
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO enderchest_state (id, data) " +
                "VALUES (1, ?) " +
                "ON CONFLICT(id) DO UPDATE SET data = excluded.data"
        )) {
            ps.setString(1, data);
            ps.executeUpdate();
        }
    }

    /**
     * Load previously persisted state from SQLite into playerFirstOpenTime.
     *
     * This method is blocking. Run it off the main server thread.
     */
    public void load(Connection connection) throws SQLException {
        // 1) Ensure table exists (safe if called multiple times)
        try (PreparedStatement ps = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS enderchest_state (" +
                "  id   INTEGER PRIMARY KEY CHECK (id = 1)," +
                "  data TEXT NOT NULL" +
                ")"
        )) {
            ps.executeUpdate();
        }

        // 2) Read row with id = 1, if any
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT data FROM enderchest_state WHERE id = 1"
        );
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                String data = rs.getString("data");
                deserialize(data); // fills playerFirstOpenTime
            } else {
                // No stored state; keep map empty
                playerFirstOpenTime.clear();
            }
        }
    }
}
