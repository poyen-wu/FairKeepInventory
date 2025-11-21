package com.fairkeepinventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.fairkeepinventory.model.InventoryId;
import com.fairkeepinventory.model.OwnershipStatus;
import com.fairkeepinventory.model.OwnershipTable;
import com.fairkeepinventory.util.Database;
import com.fairkeepinventory.util.StableOrderingMap;

public class FairKeepInventoryPlugin extends JavaPlugin implements PluginMessageListener {
    public static final String CHANNEL_ID = "fairkeepinventory:ownership";
    private final Set<UUID> moddedPlayers = ConcurrentHashMap.newKeySet();
    private static FairKeepInventoryPlugin INSTANCE;
    private final OwnershipTable ownershipTable = OwnershipTable.getInstance();

    private EnderChestClose enderChestClose = new EnderChestClose();

    @Override
    public void onEnable() {
        INSTANCE = this;
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerUseItem(), this);
        pm.registerEvents(new PlayerItemTransfer(), this);
        pm.registerEvents(new ItemDropOnDeath(), this);
        pm.registerEvents(enderChestClose, this);
        Database database = Database.getInstance();
        try {
            database.init(this);
            enderChestClose.load(database.getConnection());
            OwnershipTable.getInstance().load(database.getConnection());
        } catch (SQLException e) {
            getLogger().severe("Failed to load EnderChestClose state: " + e.getMessage());
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> { ownershipTable.tickPlayerInventory(1); }, 0L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { ownershipTable.tickEnderChestInventory(10); }, 0L, 20L);
        var messenger = getServer().getMessenger();
        messenger.registerIncomingPluginChannel(this, CHANNEL_ID, this);
        messenger.registerOutgoingPluginChannel(this, CHANNEL_ID);
    }

    @Override
    public void onDisable() {
        Database database = Database.getInstance();
        try {
            enderChestClose.persist(database.getConnection());
            OwnershipTable.getInstance().persist(database.getConnection());
        } catch (SQLException e) {
            getLogger().severe("Failed to persist EnderChestClose state: " + e.getMessage());
        }
        database.close();
    }

    public static FairKeepInventoryPlugin getInstance() {
        return INSTANCE;
    }

    // --- Plugin message handling (client mod protocol) ---

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL_ID.equals(channel)) return;
        if (message == null || message.length == 0) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            int type = in.readUnsignedByte();
            switch (type) {
                case 0x01 -> handleHello(player);
                case 0x02 -> handleTooltipRequest(player, in);
                default -> {
                    // Unknown type; ignore
                }
            }
        } catch (IOException e) {
            getLogger().warning("Failed to handle FairKeepInventory plugin message: " + e.getMessage());
        }
    }

    private void handleHello(Player player) {
        moddedPlayers.add(player.getUniqueId());
        getLogger().info("Player " + player.getName() + " has FairKeepInventory client mod.");
    }

    private void handleTooltipRequest(Player player, DataInputStream in) throws IOException {
        int requestId = in.readInt();
        byte containerType = in.readByte(); // 0 = player inventory, 1 = top inventory
        int slotIndex = in.readInt();

        List<String> lines = buildOwnershipTooltip(player, containerType, slotIndex);
        sendTooltipResponse(player, requestId, lines);
    }

    private void sendTooltipResponse(Player player, int requestId, List<String> lines) {
        if (player == null || !player.isOnline()) return;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {

            out.writeByte(0x03); // TOOLTIP_RESPONSE
            out.writeInt(requestId);

            int count = Math.min(10, lines.size()); // safety cap
            out.writeByte(count);
            for (int i = 0; i < count; i++) {
                out.writeUTF(lines.get(i));
            }

            player.sendPluginMessage(this, CHANNEL_ID, baos.toByteArray());
        } catch (IOException e) {
            getLogger().warning("Failed to send FairKeepInventory tooltip response: " + e.getMessage());
        }
    }

    /**
     * Build lines describing ownership/timer state for the given slot.
     * Called only in response to client-mod requests.
     */
    private List<String> buildOwnershipTooltip(Player player, byte containerType, int slotIndex) {
        List<String> lines = new ArrayList<>();

        // 0 = player inventory, 1 = top inventory
        Inventory inv;
        if (containerType == 0) {
            inv = player.getInventory();
        } else {
            inv = player.getOpenInventory() != null
                    ? player.getOpenInventory().getTopInventory()
                    : null;
        }

        if (inv == null) return lines;
        if (slotIndex < 0 || slotIndex >= inv.getSize()) return lines;

        ItemStack stack = inv.getItem(slotIndex);
        if (stack == null || stack.getType().isAir()) return lines;

        // Normalize to amount=1 key as OwnershipTable does
        ItemStack key = stack.asOne();

        InventoryId invId = InventoryId.from(inv);
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> invMap =
                ownershipTable.getinventory(invId);
        if (invMap == null || invMap.isEmpty()) return lines;

        StableOrderingMap<OwnershipStatus, Integer> buckets = invMap.get(key);
        if (buckets == null || buckets.isEmpty()) return lines;

        // -------- Aggregate by owner --------
        int total = 0;
        int ownedByPlayer = 0;
        int unowned = 0;
        Map<UUID, Integer> ownedByOthers = new HashMap<>();

        UUID playerId = player.getUniqueId();

        for (Map.Entry<OwnershipStatus, Integer> e : buckets.entrySet()) {
            OwnershipStatus status = e.getKey();
            int amt = e.getValue() != null ? e.getValue() : 0;
            if (amt <= 0) continue;

            total += amt;

            UUID owner = extractOwner(status); // null => unowned
            if (owner == null) {
                unowned += amt;
            } else if (owner.equals(playerId)) {
                ownedByPlayer += amt;
            } else {
                ownedByOthers.merge(owner, amt, Integer::sum);
            }
        }

        if (total <= 0) return lines;

        lines.add("§8FairKeepInventory:");

        if (ownedByPlayer > 0) {
            lines.add("§aOwned by you: §f" + ownedByPlayer + " / " + total);
        }
        if (unowned > 0) {
            lines.add("§7Unowned: §f" + unowned + " / " + total);
        }
        for (Map.Entry<UUID, Integer> e : ownedByOthers.entrySet()) {
            OfflinePlayer other = Bukkit.getOfflinePlayer(e.getKey());
            String name = (other != null && other.getName() != null)
                    ? other.getName()
                    : e.getKey().toString().substring(0, 8);
            lines.add("§cOwned by " + name + ": §f" + e.getValue());
        }

        // -------- Timer display (smallest remaining for this player) --------
        int minTimer = Integer.MAX_VALUE;

        for (Map.Entry<OwnershipStatus, Integer> e : buckets.entrySet()) {
            OwnershipStatus status = e.getKey();
            int remainingSecs = getRemainingSecondsForPlayer(status, playerId);
            if (remainingSecs >= 0) {
                minTimer = Math.min(minTimer, remainingSecs);
            }
        }

        if (minTimer != Integer.MAX_VALUE) {
            lines.add("§bTimer: §f" + minTimer + "s until fully owned");
        }

        return lines;
    }

    /**
     * Extract the logical owner for aggregation:
     * - empty() / timered() => unowned (null)
     * - owned() / claimingOwned() => owner UUID
     */
    private UUID extractOwner(OwnershipStatus status) {
        // Directly uses your Optional<UUID> field
        return status.getOwnerUuid().orElse(null);
    }

    /**
     * Return remaining timer seconds for this player in this bucket,
     * or a negative value if no relevant timer applies.
     */
    private int getRemainingSecondsForPlayer(OwnershipStatus status, UUID playerId) {
        Optional<OwnershipStatus.Timer> optTimer = status.getTimer();
        if (optTimer.isEmpty()) {
            return -1;
        }

        OwnershipStatus.Timer timer = optTimer.get();
        if (!playerId.equals(timer.getPlayerId())) {
            // Timer belongs to some other player => irrelevant for this tooltip
            return -1;
        }

        return timer.getRemainingSeconds();
    }
}
