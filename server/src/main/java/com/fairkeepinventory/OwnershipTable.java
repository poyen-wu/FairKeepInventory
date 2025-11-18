package com.fairkeepinventory;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.fairkeepinventory.util.StableOrderingMap;

public class OwnershipTable {

    private final Map<InventoryId, Map<Material, StableOrderingMap<OwnershipStatus, Integer>>> table = new HashMap<>();

    public void trackPlayerInventory(Player player) {
        Inventory inventory = player.getInventory();
        UUID playerId = player.getUniqueId();

        trackInventory(
            inventory,
            OwnershipStatus.playerOrder(playerId),
            () -> OwnershipStatus.timered(playerId)
        );
    }

    public void trackInventory(Inventory inventory) {
        trackInventory(
            inventory,
            OwnershipStatus.sharedOrder(),
            () -> OwnershipStatus.empty()
        );
    }

    private void trackInventory(
        Inventory inventory,
        Comparator<OwnershipStatus> comparator,
        Supplier<OwnershipStatus> defaultStatusSupplier
    ) {
        // For this inventory: Material -> (OwnershipStatus -> amount)
        Map<Material, StableOrderingMap<OwnershipStatus, Integer>> trackingRecords = table.computeIfAbsent(
            InventoryId.from(inventory),
            k -> new HashMap<>()
        );

        // Actual counts in the current inventory, by material.
        Map<Material, Integer> actualAmount = new HashMap<>();

        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            Material itemType = stack.getType();
            actualAmount.put(
                itemType,
                actualAmount.getOrDefault(itemType, 0) + stack.getAmount()
            );
        }

        // Subtract tracked amounts so actualAmount becomes the "difference".
        for (Map.Entry<Material, StableOrderingMap<OwnershipStatus, Integer>> entry : trackingRecords.entrySet()) {
            Material itemType = entry.getKey();
            int trackedAmount = entry.getValue().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
            actualAmount.put(itemType, actualAmount.getOrDefault(itemType, 0) - trackedAmount);
        }

        // Apply differences to trackingRecords via addItems/removeItems.
        for (Map.Entry<Material, Integer> entry : actualAmount.entrySet()) {
            Material itemType = entry.getKey();
            int difference = entry.getValue();

            if (difference == 0) {
                continue;
            }

            StableOrderingMap<OwnershipStatus, Integer> records = trackingRecords.get(itemType);

            if (difference > 0) {
                // Items were added.
                if (records == null || records.isEmpty()) {
                    // No records yet: use the default status.
                    OwnershipStatus status = defaultStatusSupplier.get();
                    addItems(inventory, itemType, status, difference, comparator);
                } else {
                    // Get the least significant ownership (first by comparator).
                    Map.Entry<OwnershipStatus, Integer> firstEntry =
                        records.entrySet().iterator().next();
                    OwnershipStatus leastSignificantStatus = firstEntry.getKey();

                    OwnershipStatus targetStatus;
                    if (leastSignificantStatus.isEmpty() || leastSignificantStatus.isNew()) {
                        // Merge extra items into the least significant status.
                        targetStatus = leastSignificantStatus;
                    } else {
                        // Add a new ownership bucket with a new status.
                        targetStatus = defaultStatusSupplier.get();
                    }

                    addItems(inventory, itemType, targetStatus, difference, comparator);
                }
            } else {
                // Items were removed: consume from the beginning of the ordered map.
                int diff = -difference;
                removeItems(inventory, itemType, diff);
            }
        }
    }

    void addItems(Inventory inventory, Material itemType, OwnershipStatus ownership, int amount) {
        addItems(
            inventory,
            itemType,
            ownership,
            amount,
            OwnershipStatus.sharedOrder()
        );
    }

    void addItems(Player player, Material itemType, OwnershipStatus ownership, int amount) {
        addItems(
            player.getInventory(),
            itemType,
            ownership,
            amount,
            OwnershipStatus.playerOrder(player.getUniqueId())
        );
    }

    private void addItems(
            Inventory inventory,
            Material itemType,
            OwnershipStatus ownership,
            int amount,
            Comparator<OwnershipStatus> comparator
    ) {
        if (amount <= 0) {
            return;
        }

        InventoryId inventoryId = InventoryId.from(inventory);

        Map<Material, StableOrderingMap<OwnershipStatus, Integer>> byMaterial =
            table.computeIfAbsent(inventoryId, k -> new HashMap<>());

        StableOrderingMap<OwnershipStatus, Integer> records =
            byMaterial.computeIfAbsent(itemType, k -> new StableOrderingMap<>(comparator, (a, b) -> a.equals(b)));

        // Merge with existing amount for this ownership, if present.
        records.merge(ownership, amount, Integer::sum);
    }

    void removeItems(Player player, Material itemType, int amount) {
        removeItems(player.getInventory(), itemType, amount);
    }

    void removeItems(Inventory inventory, Material itemType, int amount) {
        if (amount <= 0) {
            return;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        Map<Material, StableOrderingMap<OwnershipStatus, Integer>> byMaterial = table.get(inventoryId);
        if (byMaterial == null) {
            return;
        }

        StableOrderingMap<OwnershipStatus, Integer> records = byMaterial.get(itemType);
        if (records == null || records.isEmpty()) {
            return;
        }

        int remaining = amount;

        // Consume from the lowest/first entries until remaining == 0 or map is empty.
        var it = records.entrySet().iterator();
        while (remaining > 0 && it.hasNext()) {
            Map.Entry<OwnershipStatus, Integer> entry = it.next();
            int available = entry.getValue();

            if (available <= remaining) {
                // Remove entire bucket.
                it.remove();
                remaining -= available;
            } else {
                // Partially reduce this bucket.
                entry.setValue(available - remaining);
                remaining = 0;
            }
        }

        // Remove empty material/inventory entries.
        if (records.isEmpty()) {
            byMaterial.remove(itemType);
            if (byMaterial.isEmpty()) {
                table.remove(inventoryId);
            }
        }
    }

    void removeItems(Player player, Material itemType, OwnershipStatus ownership, int amount) {
        removeItems(player.getInventory(), itemType, ownership, amount);
    }

    void removeItems(Inventory inventory, Material itemType, OwnershipStatus ownership, int amount) {
        if (amount <= 0) {
            return;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        Map<Material, StableOrderingMap<OwnershipStatus, Integer>> byMaterial = table.get(inventoryId);
        if (byMaterial == null) {
            return;
        }

        StableOrderingMap<OwnershipStatus, Integer> records = byMaterial.get(itemType);
        if (records == null || records.isEmpty()) {
            return;
        }

        Integer current = records.get(ownership);
        if (current == null || current <= 0) {
            return;
        }

        if (amount >= current) {
            // Remove entire ownership bucket.
            records.remove(ownership);
        } else {
            // Just reduce by amount.
            records.put(ownership, current - amount);
        }

        if (records.isEmpty()) {
            byMaterial.remove(itemType);
            if (byMaterial.isEmpty()) {
                table.remove(inventoryId);
            }
        }
    }

    public void tickInventories(int seconds) {
        if (seconds <= 0) {
            return;
        }

        for (Map.Entry<InventoryId, Map<Material, StableOrderingMap<OwnershipStatus, Integer>>> invEntry
                : table.entrySet()) {

            InventoryId invId = invEntry.getKey();

            // Only consider player inventories
            if (!(invId instanceof InventoryId.PlayerInventoryId playerInvId)) {
                continue;
            }

            // Only tick the main PLAYER inventory type
            InventoryId.PlayerInventoryId.Id rawId =
                    (InventoryId.PlayerInventoryId.Id) playerInvId.id;
            if (rawId.inventoryType != InventoryType.PLAYER) {
                continue;
            }

            // For each material in this inventory
            for (StableOrderingMap<OwnershipStatus, Integer> records
                    : invEntry.getValue().values()) {

                if (records.isEmpty()) {
                    continue;
                }

                // Compute updated statuses while preserving stable ordering semantics
                List<Map.Entry<OwnershipStatus, Integer>> updated =
                        new ArrayList<>(records.size());

                for (Map.Entry<OwnershipStatus, Integer> e : records.entrySet()) {
                    OwnershipStatus oldStatus = e.getKey();
                    int amount = e.getValue();

                    OwnershipStatus newStatus = oldStatus.tickTimer(seconds);
                    updated.add(new AbstractMap.SimpleEntry<>(newStatus, amount));
                }

                // Rebuild the StableOrderingMap so that:
                //  - keys are the ticked OwnershipStatus values
                //  - equal statuses are merged
                //  - insertion order for ties is based on original order
                records.clear();

                for (Map.Entry<OwnershipStatus, Integer> e : updated) {
                    records.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }
    }
}
