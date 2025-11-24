package com.fairkeepinventory.model;

import java.util.ArrayList;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.fairkeepinventory.util.StableOrderingMap;

public class OwnershipTable {
    public static final class InventoryCursorStack {
        protected ItemStack itemType;
        protected StableOrderingMap<OwnershipStatus, Integer> amount;

        protected InventoryCursorStack(ItemStack itemType, StableOrderingMap<OwnershipStatus, Integer> amount) {
            this.setItemType(itemType);
            this.setAmount(amount);
        }

        public void setItemType(ItemStack itemType) {
            this.itemType = itemType.asOne();
        }

        public ItemStack getItemType() {
            return itemType;
        }

        public void setAmount(StableOrderingMap<OwnershipStatus, Integer> amount) {
            this.amount = amount;
        }

        public StableOrderingMap<OwnershipStatus, Integer> getAmount() {
            return amount;
        }
    }

    private static final OwnershipTable INSTANCE = new OwnershipTable();
    private final Map<InventoryId, Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>>> table = new HashMap<>();
    private final Map<UUID, InventoryCursorStack> cursor = new HashMap<>();
    private final Map<UUID, Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>>> playerDroppedItems = new HashMap<>();
    private final Map<UUID, StableOrderingMap<OwnershipStatus, Integer>> itemEntities = new HashMap<>();

    private OwnershipTable() {
    }

    public static OwnershipTable getInstance() {
        return INSTANCE;
    }

    public void setCursor(UUID playerId, ItemStack stack, StableOrderingMap<OwnershipStatus, Integer> amount) {
        cursor.put(playerId, new InventoryCursorStack(new ItemStack(stack), amount));
    }

    public InventoryCursorStack getCursor(UUID playerId) {
        return cursor.get(playerId);
    }

    public InventoryCursorStack takeCursor(UUID playerId) {
        return cursor.remove(playerId);
    }

    public void setItemEntityOwner(UUID itemUuid, StableOrderingMap<OwnershipStatus, Integer> status) {
        itemEntities.put(itemUuid, status);
    }

    public StableOrderingMap<OwnershipStatus, Integer> getItemEntityOwner(UUID itemUuid) {
        return itemEntities.get(itemUuid);
    }

    public void unsetItemEntityOwner(UUID itemUuid) {
        itemEntities.remove(itemUuid);
    }

    public void setPlayerDroppedItemOwner(UUID playerId, ItemStack items, StableOrderingMap<OwnershipStatus, Integer> status) {
        var droppedItems = playerDroppedItems.computeIfAbsent(playerId, k -> new HashMap<>());

        ItemStack singleItem = items.asOne();

        status.setOrderComparator((a, b) -> 0);
        droppedItems.merge(singleItem, status, (existing, incoming) -> {
            incoming.forEach((ownershipStatus, amount) ->
                existing.merge(ownershipStatus, amount, Integer::sum)
            );
            return existing;
        });
    }

    public int InstantiatePlayerDroppedItems(UUID playerId, Item itemEntity) {
        ItemStack itemStack = itemEntity.getItemStack();
        int amountNeeded = itemStack.getAmount();
        ItemStack itemType = itemStack.asOne();

        if (amountNeeded <= 0) {
            return 0;
        }

        // Retrieve the collection of items dropped by the specified player.
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> droppedItems = playerDroppedItems.get(playerId);
        if (droppedItems == null) {
            return 0; // No items tracked for this player.
        }

        // Get the ownership map for this specific item type.
        StableOrderingMap<OwnershipStatus, Integer> ownershipMap = droppedItems.get(itemType);
        if (ownershipMap == null || ownershipMap.isEmpty()) {
            return 0; // No ownership data for this item type.
        }

        StableOrderingMap<OwnershipStatus, Integer> newItemOwnership = new StableOrderingMap<>(OwnershipStatus.sharedTakeOrder(), OwnershipStatus::equals);
        int totalAmountTaken = 0;

        // Use an iterator to safely modify the map while iterating.
        Iterator<Map.Entry<OwnershipStatus, Integer>> iterator = ownershipMap.entrySet().iterator();

        while (amountNeeded > 0 && iterator.hasNext()) {
            Map.Entry<OwnershipStatus, Integer> entry = iterator.next();
            int availableAmount = entry.getValue();

            // Determine how much of this item stack can be taken.
            int amountToTake = Math.min(amountNeeded, availableAmount);

            if (amountToTake > 0) {
                // Assign the taken amount and its ownership to the new entity.
                newItemOwnership.put(entry.getKey(), amountToTake);
                totalAmountTaken += amountToTake;
                amountNeeded -= amountToTake;

                // Update the source map.
                if (availableAmount == amountToTake) {
                    // If the entire stack is taken, remove the entry.
                    iterator.remove();
                } else {
                    // Otherwise, just decrement the amount.
                    entry.setValue(availableAmount - amountToTake);
                }
            }
        }

        // If any ownership was assigned, link it to the new item entity.
        if (!newItemOwnership.isEmpty()) {
            itemEntities.put(itemEntity.getUniqueId(), newItemOwnership);
        }

        // Clean up the parent map if the ownership map for this item is now empty.
        if (ownershipMap.isEmpty()) {
            droppedItems.remove(itemType);
        }

        return totalAmountTaken;
    }

    public Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> getinventory(InventoryId inventoryId) {
        return table.get(inventoryId);
    }

    public void trackPlayerInventory(Player player) {
        Inventory inventory = player.getInventory();
        Inventory enderChestInventory = player.getEnderChest();
        UUID playerId = player.getUniqueId();

        trackInventory(
            inventory,
            OwnershipStatus.playerDropOrder(playerId),
            () -> OwnershipStatus.timered(playerId)
        );
        trackInventory(
            enderChestInventory,
            OwnershipStatus.playerDropOrder(playerId),
            () -> OwnershipStatus.owned(playerId)
        );
    }

    public void trackInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }

        InventoryId inventoryId = InventoryId.from(inventory);

        final Comparator<OwnershipStatus> comparator;
        final Supplier<OwnershipStatus> defaultStatusSupplier;

        if (inventoryId instanceof InventoryId.PlayerInventoryId playerInvId) {
            UUID playerId = playerInvId.getUuid();
            InventoryType type = playerInvId.getInventoryType();

            // For player inventories, use player drop order
            comparator = OwnershipStatus.playerDropOrder(playerId);

            // Default ownership: timered for main inventory + ender chest, empty otherwise
            if (type == InventoryType.PLAYER || type == InventoryType.ENDER_CHEST) {
                defaultStatusSupplier = () -> OwnershipStatus.timered(playerId);
            } else {
                defaultStatusSupplier = OwnershipStatus::empty;
            }
        } else {
            // Non-player inventories use shared drop order + empty ownership
            comparator = OwnershipStatus.sharedDropOrder();
            defaultStatusSupplier = OwnershipStatus::empty;
        }

        trackInventory(inventory, comparator, defaultStatusSupplier);
    }

    public void trackInventory(
        Inventory inventory,
        Comparator<OwnershipStatus> comparator,
        Supplier<OwnershipStatus> defaultStatusSupplier
    ) {
        // For this inventory: ItemStack -> (OwnershipStatus -> amount)
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> trackingRecords = table.computeIfAbsent(
            InventoryId.from(inventory),
            k -> new HashMap<>()
        );

        // Actual counts in the current inventory, by ItemStack.
        Map<ItemStack, Integer> actualAmount = new HashMap<>();

        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.isEmpty()) {
                ItemStack key = stack.asOne();
                actualAmount.put(
                    key,
                    actualAmount.getOrDefault(key, 0) + stack.getAmount()
                );
            }
        }

        // Subtract tracked amounts so actualAmount becomes the "difference".
        for (Map.Entry<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> entry : trackingRecords.entrySet()) {
            ItemStack stack = entry.getKey();
            int trackedAmount = entry.getValue().values().stream()
                .mapToInt(Integer::intValue)
                .sum();
            actualAmount.put(stack, actualAmount.getOrDefault(stack, 0) - trackedAmount);
        }

        // Apply differences to trackingRecords via addItems/removeItems.
        for (Map.Entry<ItemStack, Integer> entry : actualAmount.entrySet()) {
            ItemStack stack = entry.getKey();
            int difference = entry.getValue();

            if (difference == 0) {
                continue;
            }

            StableOrderingMap<OwnershipStatus, Integer> records = trackingRecords.get(stack);

            if (difference > 0) {
                // Items were added.
                if (records == null || records.isEmpty()) {
                    // No records yet: use the default status.
                    OwnershipStatus status = defaultStatusSupplier.get();
                    syncItemGet(inventory, stack, status, difference, comparator);
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

                    syncItemGet(inventory, stack, targetStatus, difference, comparator);
                }
            } else {
                // Items were removed: consume from the beginning of the ordered map.
                int diff = -difference;
                syncItemLost(inventory, stack, diff);
            }
        }
    }

    public void syncItemUpdate(Inventory inventory, ItemStack original, ItemStack updated) {
        var inventoryMap = table.get(InventoryId.from(inventory));
        if (inventoryMap == null) {
            return;
        }
        ItemStack originalKey = original.asOne();
        ItemStack updatedKey = updated.asOne();
        var records = inventoryMap.get(originalKey);
        inventoryMap.remove(originalKey);
        inventoryMap.put(updatedKey, records);
    }

    /**
     * Sync "items gained" for a given stack in an inventory.
     *
     * If {@code amountByOwnership} is {@code null}, this method behaves like the
     * old "auto-infer" version:
     *  - It infers how many items were gained by comparing tracked vs actual.
     *  - It chooses the target OwnershipStatus based on inventory type and
     *    the current least-significant status bucket.
     *
     * If {@code amountByOwnership} is non-null, it is treated as an explicit
     * mapping of OwnershipStatus -> amount to add:
     *  - The method does NOT infer default ownership from inventory type.
     *  - It directly adds the specified amounts into the corresponding
     *    ownership buckets (using the appropriate take-order comparator).
     */
    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemGet(
            Inventory inventory,
            ItemStack stack,
            StableOrderingMap<OwnershipStatus, Integer> amountByOwnership
    ) {
        StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> addedItems =
                new StableOrderingMap<>((a, b) -> 0, ItemStack::isSimilar);

        if (inventory == null || stack == null || stack.isEmpty()) {
            return addedItems;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        ItemStack key = stack.asOne();

        // Decide comparator + default ownership based on inventory type
        final Comparator<OwnershipStatus> takeOrder;
        final Supplier<OwnershipStatus> defaultStatusSupplier;

        if (inventoryId instanceof InventoryId.PlayerInventoryId playerInvId) {
            UUID uuid = playerInvId.getUuid();
            InventoryType type = playerInvId.getInventoryType();

            // For items entering a player inventory, use "take" order
            takeOrder = OwnershipStatus.playerTakeOrder(uuid);

            // Default ownership for main inventory / ender chest is timered(playerId); else empty
            if (type == InventoryType.PLAYER || type == InventoryType.ENDER_CHEST) {
                defaultStatusSupplier = () -> OwnershipStatus.timered(uuid);
            } else {
                defaultStatusSupplier = OwnershipStatus::empty;
            }
        } else {
            // Non-player inventories use shared/common take order + empty ownership
            takeOrder = OwnershipStatus.sharedTakeOrder();
            defaultStatusSupplier = OwnershipStatus::empty;
        }

        // Get or create the per-item tracking map
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> byItemStack =
                table.computeIfAbsent(inventoryId, k -> new HashMap<>());

        StableOrderingMap<OwnershipStatus, Integer> records = byItemStack.get(key);
        if (records == null) {
            records = new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);
            byItemStack.put(key, records);
        } else {
            records.setOrderComparator(takeOrder);
        }

        // CASE 1: explicit OwnershipStatus -> amount map provided
        if (amountByOwnership != null) {
            if (amountByOwnership.isEmpty()) {
                return addedItems;
            }

            StableOrderingMap<OwnershipStatus, Integer> addedOwnership =
                    new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);

            for (Map.Entry<OwnershipStatus, Integer> entry : amountByOwnership.entrySet()) {
                OwnershipStatus status = entry.getKey();
                int amt = entry.getValue() != null ? entry.getValue() : 0;
                if (amt <= 0) {
                    continue;
                }

                // Apply directly using the low-level helper
                syncItemGet(inventory, key, status, amt, takeOrder);

                // Track what was added for the return value
                addedOwnership.merge(status, amt, Integer::sum);
            }

            if (!addedOwnership.isEmpty()) {
                addedItems.put(key, addedOwnership);
            }

            return addedItems;
        }

        // CASE 2: no explicit ownership map => infer amount and ownership (old behavior)

        // Sum tracked amount for this item
        int trackedTotal = 0;
        for (Integer v : records.values()) {
            trackedTotal += v;
        }

        // Compute actual amount in the inventory for this item
        int actualTotal = 0;
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.isEmpty() && s.isSimilar(key)) {
                actualTotal += s.getAmount();
            }
        }

        // Infer how many items were gained
        int amount = Math.max(0, actualTotal - trackedTotal);
        if (amount <= 0) {
            return addedItems;
        }

        // Decide which ownership bucket to put the new items into
        final OwnershipStatus targetStatus;
        if (records.isEmpty()) {
            targetStatus = defaultStatusSupplier.get();
        } else {
            Map.Entry<OwnershipStatus, Integer> first = records.entrySet().iterator().next();
            OwnershipStatus least = first.getKey();
            if (least.isEmpty() || least.isNew()) {
                targetStatus = least;
            } else {
                targetStatus = defaultStatusSupplier.get();
            }
        }

        // Apply to tracking table using the low-level helper
        syncItemGet(inventory, key, targetStatus, amount, takeOrder);

        // Build return map for what was added
        StableOrderingMap<OwnershipStatus, Integer> addedOwnership =
                new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);
        addedOwnership.put(targetStatus, amount);
        addedItems.put(key, addedOwnership);

        return addedItems;
    }

    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemGet(
            Inventory inventory,
            ItemStack stack,
            OwnershipStatus ownership
    ) {
        StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> addedItems =
                new StableOrderingMap<>((a, b) -> 0, ItemStack::isSimilar);

        if (inventory == null || stack == null || stack.isEmpty()) {
            return addedItems;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        ItemStack key = stack.asOne();

        // Decide comparator based on inventory type (same pattern as other overloads)
        final Comparator<OwnershipStatus> takeOrder;
        if (inventoryId instanceof InventoryId.PlayerInventoryId playerInvId) {
            UUID uuid = playerInvId.getUuid();
            // For items entering a player inventory, use "take" order
            takeOrder = OwnershipStatus.playerTakeOrder(uuid);
        } else {
            // Non-player inventories use shared/common take order
            takeOrder = OwnershipStatus.sharedTakeOrder();
        }

        // Get or create the per-item tracking map
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> byItemStack =
                table.computeIfAbsent(inventoryId, k -> new HashMap<>());

        StableOrderingMap<OwnershipStatus, Integer> records = byItemStack.get(key);
        if (records == null) {
            records = new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);
            byItemStack.put(key, records);
        } else {
            records.setOrderComparator(takeOrder);
        }

        // Sum tracked amount for this item
        int trackedTotal = 0;
        for (Integer v : records.values()) {
            trackedTotal += v;
        }

        // Compute actual amount in the inventory for this item
        int actualTotal = 0;
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.isEmpty() && s.isSimilar(key)) {
                actualTotal += s.getAmount();
            }
        }

        // Infer how many items were gained
        int amount = Math.max(0, actualTotal - trackedTotal);
        if (amount <= 0) {
            return addedItems;
        }

        // Bukkit.getLogger().info("syncItemGet amount: " + amount);
        // Bukkit.getLogger().info("syncItemGet ownership: " + ownership);
        // Do NOT touch any existing ownership buckets (including empty/new) other than
        // merging into the explicitly provided `ownership` bucket.
        syncItemGet(inventory, key, ownership, amount, takeOrder);

        // Build return map for what was added
        StableOrderingMap<OwnershipStatus, Integer> addedOwnership =
                new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);
        addedOwnership.put(ownership, amount);
        addedItems.put(key, addedOwnership);

        return addedItems;
    }

    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemGet(
            Inventory inventory,
            ItemStack stack,
            int amount
    ) {
        return syncItemGet(inventory, stack, Optional.of(amount));
    }

    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemGet(
            Inventory inventory,
            ItemStack stack,
            Optional<Integer> amountOpt
    ) {
        StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> addedItems =
                new StableOrderingMap<>((a, b) -> 0, ItemStack::isSimilar);

        if (inventory == null || stack == null || stack.isEmpty()) {
            return addedItems;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        ItemStack key = stack.asOne();

        // Decide comparator + default ownership based on inventory type
        final Comparator<OwnershipStatus> takeOrder;
        final Supplier<OwnershipStatus> defaultStatusSupplier;

        if (inventoryId instanceof InventoryId.PlayerInventoryId playerInvId) {
            UUID uuid = playerInvId.getUuid();
            InventoryType type = playerInvId.getInventoryType();

            // For items entering a player inventory, use "take" order
            takeOrder = OwnershipStatus.playerTakeOrder(uuid);

            // Default ownership for main inventory / ender chest is timered(playerId); else empty
            if (type == InventoryType.PLAYER || type == InventoryType.ENDER_CHEST) {
                defaultStatusSupplier = () -> OwnershipStatus.timered(uuid);
            } else {
                defaultStatusSupplier = OwnershipStatus::empty;
            }
        } else {
            // Non-player inventories use shared/common take order + empty ownership
            takeOrder = OwnershipStatus.sharedTakeOrder();
            defaultStatusSupplier = OwnershipStatus::empty;
        }

        // Get or create the per-item tracking map
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> byItemStack =
                table.computeIfAbsent(inventoryId, k -> new HashMap<>());

        StableOrderingMap<OwnershipStatus, Integer> records = byItemStack.get(key);
        if (records == null) {
            records = new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);
            byItemStack.put(key, records);
        } else {
            records.setOrderComparator(takeOrder);
        }

        // Sum tracked amount for this item
        int trackedTotal = 0;
        for (Integer v : records.values()) {
            trackedTotal += v;
        }

        // Compute actual amount in the inventory for this item
        int actualTotal = 0;
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.isEmpty() && s.isSimilar(key)) {
                actualTotal += s.getAmount();
            }
        }

        // Decide how many items were gained
        final int amount;
        if (amountOpt != null && amountOpt.isPresent()) {
            int requested = amountOpt.get();
            if (requested <= 0) {
                return addedItems;
            }
            // For "get", no risk of going negative; use requested amount directly
            amount = requested;
        } else {
            // Infer gain from actual - tracked
            int inferred = Math.max(0, actualTotal - trackedTotal);
            if (inferred <= 0) {
                return addedItems;
            }
            amount = inferred;
        }

        if (amount <= 0) {
            return addedItems;
        }

        // Decide which ownership bucket to put the new items into
        final OwnershipStatus targetStatus;
        if (records.isEmpty()) {
            targetStatus = defaultStatusSupplier.get();
        } else {
            Map.Entry<OwnershipStatus, Integer> first = records.entrySet().iterator().next();
            OwnershipStatus least = first.getKey();
            if (least.isEmpty() || least.isNew()) {
                targetStatus = least;
            } else {
                targetStatus = defaultStatusSupplier.get();
            }
        }

        // Apply to tracking table using the low-level helper
        syncItemGet(inventory, key, targetStatus, amount, takeOrder);

        // Build return map for what was added
        StableOrderingMap<OwnershipStatus, Integer> addedOwnership =
                new StableOrderingMap<>(takeOrder, OwnershipStatus::equals);
        addedOwnership.put(targetStatus, amount);
        addedItems.put(key, addedOwnership);

        return addedItems;
    }

    public void syncItemGet(
            Inventory inventory,
            ItemStack stack,
            OwnershipStatus ownership,
            int amount,
            Comparator<OwnershipStatus> comparator
    ) {
        if (amount <= 0) {
            return;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        ItemStack key = stack.asOne();

        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> byItemStack =
                table.computeIfAbsent(inventoryId, k -> new HashMap<>());

        StableOrderingMap<OwnershipStatus, Integer> records =
                byItemStack.computeIfAbsent(key,
                        k -> new StableOrderingMap<>(comparator, OwnershipStatus::equals));

        // Always update the comparator, even if the map already existed
        records.setOrderComparator(comparator);

        // Merge with existing amount for this ownership, if present.
        records.merge(ownership, amount, Integer::sum);
    }

    public Map<ItemStack, Integer> removeItems(Player player, Predicate<OwnershipStatus> pred) {
        Map<ItemStack, Integer> removedItems = new HashMap<>();

        Inventory inventory = player.getInventory();
        InventoryId inventoryId = InventoryId.from(inventory);

        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> tracked = table.get(inventoryId);
        if (tracked == null) {
            return removedItems;
        }

        // Iterate over each tracked ItemStack
        var itemIt = tracked.entrySet().iterator();
        while (itemIt.hasNext()) {
            Map.Entry<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> itemEntry = itemIt.next();
            ItemStack stackKey = itemEntry.getKey(); // amount = 1 template
            StableOrderingMap<OwnershipStatus, Integer> records = itemEntry.getValue();

            if (records == null || records.isEmpty()) {
                continue;
            }

            int removedForStack = 0;

            // Remove all ownership buckets that match the predicate
            var recIt = records.entrySet().iterator();
            while (recIt.hasNext()) {
                Map.Entry<OwnershipStatus, Integer> recEntry = recIt.next();
                OwnershipStatus status = recEntry.getKey();

                if (pred.test(status)) {
                    int amount = recEntry.getValue();
                    removedForStack += amount;
                    recIt.remove();
                }
            }

            if (removedForStack > 0) {
                // Physically remove items from the player's inventory
                int remaining = removedForStack;

                for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
                    ItemStack slotStack = inventory.getItem(slot);
                    if (slotStack == null || slotStack.isEmpty()) {
                        continue;
                    }

                    // Match by similarity (type + meta, ignoring amount)
                    if (!slotStack.isSimilar(stackKey)) {
                        continue;
                    }

                    int available = slotStack.getAmount();
                    if (available <= remaining) {
                        // Consume the entire stack in this slot
                        inventory.clear(slot);
                        remaining -= available;
                    } else {
                        // Partially consume this stack
                        slotStack.setAmount(available - remaining);
                        remaining = 0;
                    }
                }

                // Track how many of this template stack were removed logically
                removedItems.merge(stackKey, removedForStack, Integer::sum);
            }

            // If no ownership records left for this ItemStack, remove it from the inventory map
            if (records.isEmpty()) {
                itemIt.remove();
            }
        }

        // If no items left for this inventory, remove the inventory entry entirely
        if (tracked.isEmpty()) {
            table.remove(inventoryId);
        }

        return removedItems;
    }

    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemLost(
            Inventory inventory,
            ItemStack stack
    ) {
        return syncItemLost(inventory, stack, Optional.empty());
    }

    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemLost(
            Inventory inventory,
            ItemStack stack,
            int amount
    ) {
        return syncItemLost(inventory, stack, Optional.of(amount));
    }

    public StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> syncItemLost(
            Inventory inventory,
            ItemStack stack,
            Optional<Integer> amountOpt
    ) {
        StableOrderingMap<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> removedItems =
                new StableOrderingMap<>((a, b) -> 0, ItemStack::isSimilar);

        if (inventory == null || stack == null || stack.isEmpty()) {
            return removedItems;
        }

        InventoryId inventoryId = InventoryId.from(inventory);
        ItemStack key = stack.asOne();

        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> byItemStack = table.get(inventoryId);
        if (byItemStack == null) {
            return removedItems;
        }

        StableOrderingMap<OwnershipStatus, Integer> records = byItemStack.get(key);
        if (records == null || records.isEmpty()) {
            return removedItems;
        }

        // Choose comparator based on inventory type
        Comparator<OwnershipStatus> dropOrder;
        if (inventoryId instanceof InventoryId.PlayerInventoryId playerInvId) {
            dropOrder = OwnershipStatus.playerDropOrder(playerInvId.getUuid());
        } else {
            dropOrder = OwnershipStatus.sharedDropOrder();
        }

        // Sum tracked amount for this item
        int trackedTotal = 0;
        for (Integer v : records.values()) {
            trackedTotal += v;
        }
        if (trackedTotal <= 0) {
            return removedItems;
        }

        // Compute actual amount in the inventory for this item
        int actualTotal = 0;
        for (ItemStack s : inventory.getContents()) {
            if (s != null && !s.isEmpty() && s.isSimilar(key)) {
                actualTotal += s.getAmount();
            }
        }

        // Decide how many items were lost
        final int amount;
        if (amountOpt != null && amountOpt.isPresent()) {
            int requested = amountOpt.get();
            if (requested <= 0) {
                return removedItems;
            }
            amount = Math.min(requested, trackedTotal);
        } else {
            int inferred = Math.max(0, trackedTotal - actualTotal);
            if (inferred <= 0) {
                return removedItems;
            }
            amount = Math.min(inferred, trackedTotal);
        }

        if (amount <= 0) {
            return removedItems;
        }

        // Initialize the result map for this ItemStack with the same comparator
        StableOrderingMap<OwnershipStatus, Integer> removedOwnership =
                new StableOrderingMap<>(dropOrder, OwnershipStatus::equals);

        // Consume from lowest/first entries according to dropOrder
        records.setOrderComparator(dropOrder);
        int remaining = amount;

        var it = records.entrySet().iterator();
        while (remaining > 0 && it.hasNext()) {
            Map.Entry<OwnershipStatus, Integer> entry = it.next();
            int available = entry.getValue();

            if (available <= remaining) {
                // Remove entire bucket and record it
                removedOwnership.put(entry.getKey(), available);
                it.remove();
                remaining -= available;
            } else {
                // Partially reduce this bucket and record the removed portion
                removedOwnership.put(entry.getKey(), remaining);
                entry.setValue(available - remaining);
                remaining = 0;
            }
        }

        // Add to result map if any items were removed
        if (!removedOwnership.isEmpty()) {
            removedItems.put(key, removedOwnership);
        }

        // Cleanup empty structures
        if (records.isEmpty()) {
            byItemStack.remove(key);
            if (byItemStack.isEmpty()) {
                table.remove(inventoryId);
            }
        }

        return removedItems;
    }

    /**
     * Syncs moving items from source to destination. Calculates the difference
     * between the tracked table and actual inventories and then updates the
     * tracked table.
     * 
     * If either side of the inventory is already in the tracking table, this
     * method uses that to calculate the amount of items moved, and updates the
     * ownership status on both sides accordingly.
     * 
     * If the source inventory is tracked but not the destination, it calculates
     * the amount of items lost in the source inventory, sets the comparator of
     * the tracked item ownership to the proper comparator, initializes tracking
     * of the destination inventory, and moves the amount of smallest ownership
     * statuses (begining of the map) to the destination. Same logic if the
     * destination is tracked but not the source.
     * 
     * If either side of the inventory is a player inventory (any type), the
     * comparator OwnershipStatus.playerDropOrder is used, otherwise, the
     * comparator OwnershipStatus.defaultDropOrder is used when moving items
     * from a player inventory to other inventory. OwnershipStatus.playerTakeOrder
     * and OwnershipStatus.defaultTakeOrder are used instead. When both sides
     * are player inventories, it identifies the main player inventory, and
     * moves items in the same logic above as if the other side is not a player
     * inventory.
     * 
     * Simply tracks both inventories if both were not tracked yet.
     * 
     * Default ownership statuses are used when both inventories are not tracked
     * or when proper ownership status can't be inferred due to out of sync
     * tracked and actual inventory. The default ownership status for player's
     * main inventory and ender chest is OwnershipStatus.timered(playerId), the
     * default for all other inventory is OwnershipStatus.empty().
     * 
     * @param source source inventory that the items were moved from
     * @param destination destination inventory that the items were moved to
     * @param stack type of the items that were moved
     */
    public void syncItemTransfer(Inventory source, Inventory destination, ItemStack stack) {
        if (source == null || destination == null || stack == null || stack.isEmpty()) {
            return;
        }
        if (source.equals(destination)) {
            return;
        }

        ItemStack key = stack.asOne();

        InventoryId srcId = InventoryId.from(source);
        InventoryId dstId = InventoryId.from(destination);

        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> srcTrackedMap = table.get(srcId);
        Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> dstTrackedMap = table.get(dstId);

        StableOrderingMap<OwnershipStatus, Integer> srcRecords =
                srcTrackedMap != null ? srcTrackedMap.get(key) : null;
        StableOrderingMap<OwnershipStatus, Integer> dstRecords =
                dstTrackedMap != null ? dstTrackedMap.get(key) : null;

        boolean srcTracked = srcRecords != null && !srcRecords.isEmpty();
        boolean dstTracked = dstRecords != null && !dstRecords.isEmpty();

        // Figure out player info for both sides
        final boolean srcIsPlayer = srcId instanceof InventoryId.PlayerInventoryId;
        final boolean dstIsPlayer = dstId instanceof InventoryId.PlayerInventoryId;

        final InventoryId.PlayerInventoryId srcPlayerId =
                srcIsPlayer ? (InventoryId.PlayerInventoryId) srcId : null;
        final InventoryId.PlayerInventoryId dstPlayerId =
                dstIsPlayer ? (InventoryId.PlayerInventoryId) dstId : null;

        final InventoryType srcType;
        final UUID srcUuid;
        if (srcIsPlayer) {
            srcType = srcPlayerId.getInventoryType();
            srcUuid = srcPlayerId.getUuid();
        } else {
            srcType = null;
            srcUuid = null;
        }

        final InventoryType dstType;
        final UUID dstUuid;
        if (dstIsPlayer) {
            dstType = dstPlayerId.getInventoryType();
            dstUuid = dstPlayerId.getUuid();
        } else {
            dstType = null;
            dstUuid = null;
        }

        final boolean srcMain = srcIsPlayer && srcType == InventoryType.PLAYER;
        final boolean dstMain = dstIsPlayer && dstType == InventoryType.PLAYER;

        // Decide which side is treated as "player" when both are player inventories
        final boolean treatSrcAsPlayer;
        final boolean treatDstAsPlayer;
        if (srcIsPlayer && dstIsPlayer) {
            if (srcMain && !dstMain) {
                // Source is main, dest is not
                treatSrcAsPlayer = true;
                treatDstAsPlayer = false;
            } else if (!srcMain && dstMain) {
                // Dest is main, source is not
                treatSrcAsPlayer = false;
                treatDstAsPlayer = true;
            } else {
                // Both main or both not-main: prefer source as player side
                treatSrcAsPlayer = true;
                treatDstAsPlayer = false;
            }
        } else {
            treatSrcAsPlayer = srcIsPlayer;
            treatDstAsPlayer = dstIsPlayer;
        }

        final Comparator<OwnershipStatus> srcDropOrder = treatSrcAsPlayer
                ? OwnershipStatus.playerDropOrder(srcUuid)
                : OwnershipStatus.sharedDropOrder();
        final Comparator<OwnershipStatus> dstTakeOrder = treatDstAsPlayer
                ? OwnershipStatus.playerTakeOrder(dstUuid)
                : OwnershipStatus.sharedTakeOrder();

        // Default-ownership suppliers use only final/effectively-final variables
        // final Supplier<OwnershipStatus> srcDefaultSupplier = () -> {
        //     if (treatSrcAsPlayer &&
        //             (srcType == InventoryType.PLAYER || srcType == InventoryType.ENDER_CHEST)) {
        //         return OwnershipStatus.timered(srcUuid);
        //     }
        //     return OwnershipStatus.empty();
        // };

        final Supplier<OwnershipStatus> dstDefaultSupplier = () -> {
            if (treatDstAsPlayer &&
                    (dstType == InventoryType.PLAYER || dstType == InventoryType.ENDER_CHEST)) {
                return OwnershipStatus.timered(dstUuid);
            }
            return OwnershipStatus.empty();
        };

        // Count actual amounts of this key in an inventory
        java.util.function.BiFunction<Inventory, ItemStack, Integer> countActual = (inv, k) -> {
            int total = 0;
            for (ItemStack s : inv.getContents()) {
                if (s != null && !s.isEmpty() && s.isSimilar(k)) {
                    total += s.getAmount();
                }
            }
            return total;
        };

        int actualSrc = countActual.apply(source, key);
        int actualDst = countActual.apply(destination, key);

        // Sum tracked amounts
        java.util.function.Function<StableOrderingMap<OwnershipStatus, Integer>, Integer> sumTracked = recs -> {
            if (recs == null || recs.isEmpty()) return 0;
            int sum = 0;
            for (Integer v : recs.values()) {
                sum += v;
            }
            return sum;
        };

        int trackedSrc = sumTracked.apply(srcRecords);
        int trackedDst = sumTracked.apply(dstRecords);

        // Bukkit.getLogger().info("Tracked in src: " + trackedSrc + ", actual in src: " + actualSrc);
        // Bukkit.getLogger().info("Tracked in dst: " + trackedDst + ", actual in dst: " + actualDst);

        // If neither side is tracked, just track both inventories
        if (!srcTracked && !dstTracked) {
            if (srcIsPlayer) {
                trackInventory(source,
                        OwnershipStatus.playerDropOrder(srcUuid),
                        () -> OwnershipStatus.timered(srcUuid));
            } else {
                trackInventory(source,
                        OwnershipStatus.sharedDropOrder(),
                        OwnershipStatus::empty);
            }

            if (dstIsPlayer) {
                trackInventory(destination,
                        OwnershipStatus.playerDropOrder(dstUuid),
                        () -> OwnershipStatus.timered(dstUuid));
            } else {
                trackInventory(destination,
                        OwnershipStatus.sharedDropOrder(),
                        OwnershipStatus::empty);
            }
            return;
        }

        int lostFromSource = srcTracked ? Math.max(0, trackedSrc - actualSrc) : 0;
        int gainedAtDest = dstTracked ? Math.max(0, actualDst - trackedDst) : 0;

        final int matchedTransfer;
        if (srcTracked && dstTracked) {
            matchedTransfer = Math.min(lostFromSource, gainedAtDest);
        } else if (srcTracked) {
            matchedTransfer = lostFromSource;
        } else {
            matchedTransfer = gainedAtDest;
        }

        int remainingLossToRemove = lostFromSource;
        int remainingToTransfer = matchedTransfer;
        List<Map.Entry<OwnershipStatus, Integer>> movedStatusParts = new ArrayList<>();

        // Consume source by drop order and remember what status parts moved
        if (srcTracked && remainingLossToRemove > 0) {
            srcRecords.setOrderComparator(srcDropOrder);
            var it = srcRecords.entrySet().iterator();
            while (remainingLossToRemove > 0 && it.hasNext()) {
                Map.Entry<OwnershipStatus, Integer> e = it.next();
                int available = e.getValue();
                int consume = Math.min(available, remainingLossToRemove);

                int portionToTransfer = Math.min(consume, Math.max(0, remainingToTransfer));
                if (portionToTransfer > 0) {
                    movedStatusParts.add(
                            new AbstractMap.SimpleEntry<>(e.getKey(), portionToTransfer));
                    remainingToTransfer -= portionToTransfer;
                }

                if (consume == available) {
                    it.remove();
                } else {
                    e.setValue(available - consume);
                }
                remainingLossToRemove -= consume;
            }

            if (srcRecords.isEmpty()) {
                srcTrackedMap.remove(key);
                if (srcTrackedMap.isEmpty()) {
                    table.remove(srcId);
                }
            }
        }

        // Ensure destination records exist when needed
        Supplier<StableOrderingMap<OwnershipStatus, Integer>> ensureDstRecords = () -> {
            Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> byItem =
                    table.computeIfAbsent(dstId, k -> new HashMap<>());
            StableOrderingMap<OwnershipStatus, Integer> recs = byItem.get(key);
            if (recs == null) {
                recs = new StableOrderingMap<>(dstTakeOrder, OwnershipStatus::equals);
                byItem.put(key, recs);
            } else {
                recs.setOrderComparator(dstTakeOrder);
            }
            return recs;
        };

        // Add "default" ownership into destination following add semantics
        java.util.function.BiConsumer<Integer, StableOrderingMap<OwnershipStatus, Integer>> addDefaultToDest =
                (amt, recs) -> {
                    if (amt <= 0) return;
                    OwnershipStatus target;
                    if (recs.isEmpty()) {
                        target = dstDefaultSupplier.get();
                    } else {
                        Map.Entry<OwnershipStatus, Integer> first = recs.entrySet().iterator().next();
                        OwnershipStatus least = first.getKey();
                        if (least.isEmpty() || least.isNew()) {
                            // Replace empty statuses with timered when destination is player inventory
                            if (treatDstAsPlayer && least.isEmpty()) {
                                target = OwnershipStatus.timered(dstUuid);
                            } else {
                                target = least;
                            }
                        } else {
                            target = dstDefaultSupplier.get();
                        }
                    }
                    syncItemGet(destination, key, target, amt, dstTakeOrder);
                };

        // 1) Apply the matched transfer to destination
        if (matchedTransfer > 0) {
            StableOrderingMap<OwnershipStatus, Integer> recs = ensureDstRecords.get();

            if (!movedStatusParts.isEmpty()) {
                for (Map.Entry<OwnershipStatus, Integer> part : movedStatusParts) {
                    OwnershipStatus statusToAdd = part.getKey();
                    
                    // Replace empty statuses with timered when moving to player inventory
                    if (treatDstAsPlayer && statusToAdd.isEmpty()) {
                        statusToAdd = OwnershipStatus.timered(dstUuid);
                    }
                    
                    syncItemGet(destination, key, statusToAdd, part.getValue(), dstTakeOrder);
                }
            } else {
                addDefaultToDest.accept(matchedTransfer, recs);
            }
        }

        // 2) If destination gained more than matchedTransfer, treat extra as default
        if (dstTracked && gainedAtDest > matchedTransfer) {
            StableOrderingMap<OwnershipStatus, Integer> recs = ensureDstRecords.get();
            int extra = gainedAtDest - matchedTransfer;
            addDefaultToDest.accept(extra, recs);
        }

        // Any extra loss at source beyond matchedTransfer was already removed from srcRecords.
    }

    public void tickPlayerInventory(int seconds) {
        tickInventory(
                seconds,
                invId ->
                        invId instanceof InventoryId.PlayerInventoryId playerInvId
                                && ((InventoryId.PlayerInventoryId.Id) playerInvId.id).inventoryType == InventoryType.PLAYER
                                && Optional.ofNullable(Bukkit.getPlayer(playerInvId.getPlayerId())).map(Player::isOnline).orElse(false),
                this::reInitInPlayerInventory
        );
    }

    public void tickEnderChestInventory(int seconds) {
        tickInventory(
                seconds,
                invId ->
                        invId instanceof InventoryId.PlayerInventoryId playerInvId
                                && ((InventoryId.PlayerInventoryId.Id) playerInvId.id).inventoryType == InventoryType.ENDER_CHEST,
                this::reInitInPlayerInventory
        );
    }

    /**
     * Generic inventory ticking logic with a pre-tick transform.
     * The predicate controls *which* inventories are ticked (by InventoryId only),
     * and the transformer can rewrite OwnershipStatus before ticking.
     */
    public void tickInventory(
            int seconds,
            Predicate<InventoryId> shouldTick,
            BiFunction<InventoryId, OwnershipStatus, OwnershipStatus> preTickTransform
    ) {
        if (seconds <= 0) {
            return;
        }

        for (Map.Entry<InventoryId, Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>>> invEntry
                : table.entrySet()) {

            InventoryId invId = invEntry.getKey();

            // Skip inventories that do not match the caller's criteria
            if (!shouldTick.test(invId)) {
                continue;
            }

            // For each ItemStack in this inventory
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

                    // First, allow the caller to transform "empty" statuses, etc.
                    OwnershipStatus transformed =
                            preTickTransform.apply(invId, oldStatus);

                    // Then apply the timer tick
                    OwnershipStatus newStatus = transformed.tickTimer(seconds);
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

    private OwnershipStatus reInitInPlayerInventory(InventoryId invId, OwnershipStatus status) {
        // Only care about player inventories
        if (!(invId instanceof InventoryId.PlayerInventoryId playerInvId)) {
            return status;
        }
        UUID playerId = playerInvId.getPlayerId();

        if (status.isOwnedBy(playerId)) {
            return status;
        }
        if (status.isOwned() && !status.isTimered()) {
            return OwnershipStatus.claimingOwned(status.getOwnerUuid().get(), playerId);
        }
        if (status.isOwned() && status.isTimered() && status.getTimer().get().getPlayerId() != playerId) {
            return OwnershipStatus.claimingOwned(status.getOwnerUuid().get(), playerId);
        }
        if (status.isEmpty()) {
            return OwnershipStatus.timered(playerId);
        }
        if (status.isTimered() && status.getTimer().get().getPlayerId() == playerId) {
            return status;
        }
        if (status.isTimered() && status.getTimer().get().getPlayerId() != playerId) {
            return OwnershipStatus.timered(playerId);
        }

        return OwnershipStatus.timered(playerId);
    }

    // ---------------------------------------------------
    // Persistence
    // ---------------------------------------------------

    public void persist(Connection connection) throws SQLException {
        // Create tables if needed
        try (PreparedStatement ps = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS ownership_inventories (" +
                "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  inventory_id TEXT    NOT NULL," +
                "  itemstack    BLOB    NOT NULL," +
                "  ownership    TEXT    NOT NULL" +
                ")"
        )) {
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS ownership_item_entities (" +
                "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  entity_uuid  TEXT    NOT NULL," +
                "  ownership    TEXT    NOT NULL" +
                ")"
        )) {
            ps.executeUpdate();
        }

        // Clear existing rows
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM ownership_inventories"
        )) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM ownership_item_entities"
        )) {
            ps.executeUpdate();
        }

        // Insert entries for `table`
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ownership_inventories (inventory_id, itemstack, ownership) " +
                "VALUES (?, ?, ?)"
        )) {
            for (Map.Entry<InventoryId, Map<ItemStack, StableOrderingMap<OwnershipStatus, Integer>>> outer
                    : table.entrySet()) {

                String inventoryIdStr = outer.getKey().serialize();

                for (Map.Entry<ItemStack, StableOrderingMap<OwnershipStatus, Integer>> inner
                        : outer.getValue().entrySet()) {

                    ItemStack item = inner.getKey();
                    StableOrderingMap<OwnershipStatus, Integer> amount = inner.getValue();

                    byte[] itemBytes = item.serializeAsBytes();
                    String ownershipStr = amount.serialize(
                        OwnershipStatus::serialize,
                        v -> Integer.toString(v)
                    );

                    ps.setString(1, inventoryIdStr);
                    ps.setBytes(2, itemBytes);
                    ps.setString(3, ownershipStr);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }

        // Insert entries for `itemEntities`
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ownership_item_entities (entity_uuid, ownership) " +
                "VALUES (?, ?)"
        )) {
            for (Map.Entry<UUID, StableOrderingMap<OwnershipStatus, Integer>> entry : itemEntities.entrySet()) {
                String uuidStr = entry.getKey().toString();
                StableOrderingMap<OwnershipStatus, Integer> amount = entry.getValue();

                String ownershipStr = amount.serialize(
                    OwnershipStatus::serialize,
                    v -> Integer.toString(v)
                );

                ps.setString(1, uuidStr);
                ps.setString(2, ownershipStr);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void load(Connection connection) throws SQLException {
        // Ensure tables exist (no-ops if already created)
        try (PreparedStatement ps = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS ownership_inventories (" +
                "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  inventory_id TEXT    NOT NULL," +
                "  itemstack    BLOB    NOT NULL," +
                "  ownership    TEXT    NOT NULL" +
                ")"
        )) {
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS ownership_item_entities (" +
                "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  entity_uuid  TEXT    NOT NULL," +
                "  ownership    TEXT    NOT NULL" +
                ")"
        )) {
            ps.executeUpdate();
        }

        // Clear in-memory state
        table.clear();
        itemEntities.clear();

        // Load `table`
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT inventory_id, itemstack, ownership FROM ownership_inventories"
        );
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String inventoryIdStr = rs.getString("inventory_id");
                byte[] itemBytes = rs.getBytes("itemstack");
                String ownershipStr = rs.getString("ownership");

                InventoryId inventoryId = InventoryId.deserialize(inventoryIdStr);
                ItemStack item = ItemStack.deserializeBytes(itemBytes);
                StableOrderingMap<OwnershipStatus, Integer> amount =
                    StableOrderingMap.deserialize(
                        ownershipStr,
                        OwnershipStatus::deserialize,
                        Integer::parseInt
                    );

                table
                    .computeIfAbsent(inventoryId, k -> new HashMap<>())
                    .put(item, amount);
            }
        }

        // Load `itemEntities`
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT entity_uuid, ownership FROM ownership_item_entities"
        );
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String uuidStr = rs.getString("entity_uuid");
                String ownershipStr = rs.getString("ownership");

                UUID uuid = UUID.fromString(uuidStr);
                StableOrderingMap<OwnershipStatus, Integer> amount =
                    StableOrderingMap.deserialize(
                        ownershipStr,
                        OwnershipStatus::deserialize,
                        Integer::parseInt
                    );

                itemEntities.put(uuid, amount);
            }
        }
    }
}
