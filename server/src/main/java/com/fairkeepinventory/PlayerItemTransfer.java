package com.fairkeepinventory;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.fairkeepinventory.model.InventoryId;
import com.fairkeepinventory.model.OwnershipStatus;
import com.fairkeepinventory.model.OwnershipTable;
import com.fairkeepinventory.util.StableOrderingMap;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;

public class PlayerItemTransfer implements Listener {
    protected OwnershipTable table = OwnershipTable.getInstance();
    
    // Blocking queue for thread synchronization
    private final Map<UUID, BlockingQueue<StableOrderingMap<OwnershipStatus, Integer>>> pendingDropQueues = new ConcurrentHashMap<>();
    
    private BlockingQueue<StableOrderingMap<OwnershipStatus, Integer>> getOrCreateQueue(UUID playerId) {
        return pendingDropQueues.computeIfAbsent(playerId, k -> new LinkedBlockingQueue<>());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Item itemEntity = event.getItemDrop();
        UUID playerId = player.getUniqueId();
        
        // Spawn thread to wait for ownership data from inventory event
        new Thread(() -> {
            try {
                BlockingQueue<StableOrderingMap<OwnershipStatus, Integer>> queue = getOrCreateQueue(playerId);
                // Wait for signal with timeout
                Bukkit.getLogger().info("waiting for signal");
                StableOrderingMap<OwnershipStatus, Integer> ownership = queue.poll(100, TimeUnit.MILLISECONDS);
                Bukkit.getLogger().info("received ownership: " + ownership);

                if (ownership != null) {
                    // Got ownership data - register immediately
                    Bukkit.getScheduler().runTask(FairKeepInventoryPlugin.getInstance(), () -> {
                        table.setItemEntityOwner(itemEntity.getUniqueId(), ownership);
                    });
                } else {
                    // Timeout - fallback to delayed registration
                    Bukkit.getScheduler().runTaskLater(FairKeepInventoryPlugin.getInstance(), () -> {
                        table.InstantiatePlayerDroppedItems(playerId, itemEntity);
                    }, 2L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Bukkit.getScheduler().runTaskLater(FairKeepInventoryPlugin.getInstance(), () -> {
                    table.InstantiatePlayerDroppedItems(playerId, itemEntity);
                }, 2L);
            }
        }).start();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            return;
        }

        if (event.getCause() == EntityRemoveEvent.Cause.MERGE && event.getEntity() instanceof Item removedItem) {
            Bukkit.getLogger().info("merging item entities");
            // Capture data IMMEDIATELY before the entity is fully removed
            Bukkit.getLogger().info("removed entity: " + removedItem.getAsString());
            final ItemStack removedItemStack = new ItemStack (removedItem.getItemStack());
            final Location removedLocation = removedItem.getLocation().clone();
            final UUID removedUuid = removedItem.getUniqueId();
            Bukkit.getLogger().info("removed items: " + removedItemStack);
            Bukkit.getLogger().info("removed location: " + removedLocation);

            // Now delay to ensure thread completion
            Bukkit.getScheduler().runTaskLater(FairKeepInventoryPlugin.getInstance(), () -> {
                var removedOwnership = table.getItemEntityOwner(removedUuid);
                Bukkit.getLogger().info("removed ownership: " + removedOwnership);

                if (removedOwnership != null && !removedOwnership.isEmpty()) {
                    Item nearestSameItem = null;
                    double nearestDistance = Double.MAX_VALUE;

                    // Use the captured data, not the dead entity
                    for (Entity nearbyEntity : removedLocation.getWorld().getNearbyEntities(
                            removedLocation, 2.0, 2.0, 2.0)) {
                        if (nearbyEntity instanceof Item && nearbyEntity.getUniqueId() != removedUuid) {
                            Item nearbyItem = (Item) nearbyEntity;

                            if (nearbyItem.getItemStack().isSimilar(removedItemStack)) {
                                double distance = nearbyItem.getLocation().distance(removedLocation);
                                if (distance < nearestDistance) {
                                    nearestDistance = distance;
                                    nearestSameItem = nearbyItem;
                                }
                            }
                        }
                    }

                    Bukkit.getLogger().info("nearest same item: " + nearestSameItem);
                    if (nearestSameItem != null) {
                        final Item targetItem = nearestSameItem;
                        var existingOwnership = table.getItemEntityOwner(targetItem.getUniqueId());
                        Bukkit.getLogger().info("existing ownership: " + existingOwnership);

                        if (existingOwnership == null || existingOwnership.isEmpty()) {
                            table.setItemEntityOwner(targetItem.getUniqueId(), removedOwnership);
                        } else {
                            StableOrderingMap<OwnershipStatus, Integer> merged = new StableOrderingMap<>(
                                existingOwnership.getOrderComparator(),
                                OwnershipStatus::equals
                            );
                            merged.putAll(existingOwnership);

                            for (var entry : removedOwnership.entrySet()) {
                                merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
                            }
                            Bukkit.getLogger().info("merged ownership: " + merged);

                            table.setItemEntityOwner(targetItem.getUniqueId(), merged);
                        }
                    }
                }
                
                table.unsetItemEntityOwner(removedUuid);
            }, 3L);
            return;
        }
        
        Bukkit.getScheduler().runTaskLater(
            FairKeepInventoryPlugin.getInstance(), 
            () -> table.unsetItemEntityOwner(event.getEntity().getUniqueId()), 
            20L
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        Item itemEntity = event.getItem();
        UUID itemEntityUuid = itemEntity.getUniqueId();
        ItemStack item = itemEntity.getItemStack().asOne();
        Inventory inventory = InventoryId.from(entity).getInventory();
        table.trackInventory(inventory);
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> {
                var status = table.getItemEntityOwner(itemEntityUuid);
                table.unsetItemEntityOwner(itemEntityUuid);
                
                // Convert empty statuses to new timers if entity is a player
                if (entity instanceof Player player) {
                    if (status != null) {
                        status.setOrderComparator(OwnershipStatus.playerTakeOrder(player.getUniqueId()));
                        table.syncItemGet(inventory, item, status);
                    } else {
                        table.syncItemGet(inventory, item, Optional.empty());
                    }
                } else {
                    // Non-player entities get the status as-is
                    if (status != null) {
                        table.syncItemGet(inventory, item, status);
                    } else {
                        
                    }
                }
                if (status != null && !status.isEmpty()) {
                    table.setItemEntityOwner(itemEntityUuid, status);
                }
            }
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {}

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        table.trackPlayerInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        Bukkit.getScheduler().runTaskLater(
            FairKeepInventoryPlugin.getInstance(), () -> {
            table.trackPlayerInventory(event.getPlayer());
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        ItemStack oldCursor = event.getOldCursor();
        ItemStack newCursor = event.getCursor();
        Map<Integer, ItemStack> newItems = event.getNewItems();
        
        if (newItems.isEmpty()) return;
        
        Inventory topInventory = event.getView().getTopInventory();
        Inventory bottomInventory = event.getView().getBottomInventory();
        int topSize = topInventory.getSize();
        
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> {
                var cursorStatus = table.getCursor(player.getUniqueId());
                
                // Create a working copy with remaining amounts
                StableOrderingMap<OwnershipStatus, Integer> remainingCursor = new StableOrderingMap<>(
                    cursorStatus.getAmount().getOrderComparator(),
                    OwnershipStatus::equals
                );
                remainingCursor.putAll(cursorStatus.getAmount());
                
                // Process items placed in top inventory (non-player inventory) - use playerDropOrder
                for (Map.Entry<Integer, ItemStack> entry : newItems.entrySet()) {
                    int rawSlot = entry.getKey();
                    ItemStack itemStack = entry.getValue();
                    
                    if (rawSlot < topSize) {
                        // Re-sort remaining cursor with playerDropOrder
                        StableOrderingMap<OwnershipStatus, Integer> dropOrderCursor = new StableOrderingMap<>(
                            OwnershipStatus.playerDropOrder(player.getUniqueId()),
                            OwnershipStatus::equals
                        );
                        dropOrderCursor.putAll(remainingCursor);
                        
                        StableOrderingMap<OwnershipStatus, Integer> statusToPlace = new StableOrderingMap<>(
                            OwnershipStatus.playerDropOrder(player.getUniqueId()),
                            OwnershipStatus::equals
                        );
                        
                        int amountToPlace = itemStack.getAmount();
                        for (var statusEntry : dropOrderCursor.entrySet()) {
                            if (amountToPlace <= 0) break;
                            
                            int available = statusEntry.getValue();
                            int toTake = Math.min(available, amountToPlace);
                            
                            statusToPlace.put(statusEntry.getKey(), toTake);
                            remainingCursor.merge(statusEntry.getKey(), -toTake, Integer::sum);
                            amountToPlace -= toTake;
                        }
                        
                        table.syncItemGet(topInventory, cursorStatus.getItemType(), statusToPlace);
                        table.trackInventory(topInventory);
                    }
                }
                
                // Process items placed in bottom inventory (player's inventory) - use playerTakeOrder
                for (Map.Entry<Integer, ItemStack> entry : newItems.entrySet()) {
                    int rawSlot = entry.getKey();
                    ItemStack itemStack = entry.getValue();
                    
                    if (rawSlot >= topSize) {
                        // Re-sort remaining cursor with playerTakeOrder
                        StableOrderingMap<OwnershipStatus, Integer> takeOrderCursor = new StableOrderingMap<>(
                            OwnershipStatus.playerTakeOrder(player.getUniqueId()),
                            OwnershipStatus::equals
                        );
                        takeOrderCursor.putAll(remainingCursor);
                        
                        StableOrderingMap<OwnershipStatus, Integer> statusToPlace = new StableOrderingMap<>(
                            OwnershipStatus.playerTakeOrder(player.getUniqueId()),
                            OwnershipStatus::equals
                        );
                        
                        int amountToPlace = itemStack.getAmount();
                        for (var statusEntry : takeOrderCursor.entrySet()) {
                            if (amountToPlace <= 0) break;
                            
                            int available = statusEntry.getValue();
                            int toTake = Math.min(available, amountToPlace);
                            
                            statusToPlace.put(statusEntry.getKey(), toTake);
                            remainingCursor.merge(statusEntry.getKey(), -toTake, Integer::sum);
                            amountToPlace -= toTake;
                        }
                        
                        table.syncItemGet(bottomInventory, cursorStatus.getItemType(), statusToPlace);
                        table.trackInventory(bottomInventory);
                    }
                }
                
                // Clean up zero-amount entries
                remainingCursor.entrySet().removeIf(entry -> entry.getValue() <= 0);
                
                // Update cursor with remaining items
                if (!remainingCursor.isEmpty()) {
                    table.setCursor(player.getUniqueId(), cursorStatus.getItemType(), remainingCursor);
                } else {
                    table.takeCursor(player.getUniqueId());
                }
            }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack cursorItemStack = event.getCursor();
        ItemStack clickedItemStack = event.getCurrentItem();
        Inventory top = event.getView().getTopInventory();
        Inventory bottom = event.getView().getBottomInventory();
        Inventory clickedInventory = event.getClickedInventory();

        switch (event.getAction()) {
            case NOTHING:
                break;
            case PICKUP_ALL:
            case PICKUP_SOME:
            case PICKUP_HALF:
            case PICKUP_ONE: {
                ItemStack pickedUp = new ItemStack(clickedItemStack);
                table.trackInventory(event.getClickedInventory());
                // Bukkit.getLogger().info("Before pick up: " + Arrays.asList(clickedInventory.getContents()));
                Bukkit.getScheduler().runTask(
                    FairKeepInventoryPlugin.getInstance(),
                    () -> {
                        // Bukkit.getLogger().info("After picked up: " + Arrays.asList(clickedInventory.getContents()));
                        // Bukkit.getLogger().info("Picked up stack: " + pickedUp);
                        var status = table.syncItemLost(clickedInventory, pickedUp).get(pickedUp.asOne());
                        // for (var each: status.entrySet()) {
                        //     Bukkit.getLogger().info(
                        //         "owner: " + (each.getKey().isOwned() ? each.getKey().getOwnerUuid() : "") +
                        //         "timer: " + (each.getKey().isTimered() ? each.getKey().getTimer().get().getRemainingSeconds() : "") +
                        //         "amount: " + each.getValue()
                        //     );
                        // }
                        table.setCursor(player.getUniqueId(), pickedUp.asOne(), status);
                    });
                break;
            }
            case PLACE_ALL:
            case PLACE_SOME:
            case PLACE_ONE: {
                table.trackInventory(event.getClickedInventory());
                ItemStack placedItem = cursorItemStack.asOne();
                Bukkit.getScheduler().runTask(
                    FairKeepInventoryPlugin.getInstance(),
                    () -> {
                        var status = table.takeCursor(player.getUniqueId());
                        if (status != null) {
                            table.syncItemGet(clickedInventory, status.getItemType(), status.getAmount());
                        } else {
                            Bukkit.getLogger().warning("PLACE_ONE: cursor is empty");
                            table.syncItemGet(clickedInventory, placedItem, Optional.empty());
                        }
                    });
                break;
            }
            case SWAP_WITH_CURSOR: {
                // Bukkit.getLogger().info("SWAP_WITH_CURSOR");
                final ItemStack cursor = new ItemStack(cursorItemStack);
                final ItemStack clicked = new ItemStack(clickedItemStack);
                Bukkit.getScheduler().runTask(FairKeepInventoryPlugin.getInstance(), () -> {
                    table.syncItemGet(clickedInventory, cursor, table.takeCursor(player.getUniqueId()).getAmount());
                    var status = table.syncItemLost(clickedInventory, clicked, clicked.getAmount()).get(clicked.asOne());
                    table.setCursor(player.getUniqueId(), clicked.asOne(), status);
                });
                break;
            }
            case DROP_ALL_CURSOR: {
                var status = table.takeCursor(player.getUniqueId());
                var amount = status.getAmount();
                
                StableOrderingMap<OwnershipStatus, Integer> amountWithoutTimers = new StableOrderingMap<>(
                    amount.getOrderComparator(),
                    OwnershipStatus::equals
                );
                
                for (var entry : amount.entrySet()) {
                    OwnershipStatus originalStatus = entry.getKey();
                    Integer count = entry.getValue();
                    
                    OwnershipStatus transformedStatus;
                    if (originalStatus.isOwned()) {
                        transformedStatus = OwnershipStatus.owned(originalStatus.getOwnerUuid().get());
                    } else if (originalStatus.isTimered()) {
                        transformedStatus = OwnershipStatus.empty();
                    } else {
                        transformedStatus = originalStatus;
                    }
                    
                    amountWithoutTimers.merge(transformedStatus, count, Integer::sum);
                }
                
                // Signal waiting thread
                getOrCreateQueue(player.getUniqueId()).offer(amountWithoutTimers);
                break;
            }
            
            case DROP_ONE_CURSOR: {
                var status = table.getCursor(player.getUniqueId());
                var amount = status.getAmount();
                amount.setOrderComparator(OwnershipStatus.playerDropOrder(player.getUniqueId()));
                var first = amount.entrySet().stream().findFirst().orElse(null);
                if (first != null) {
                    int amountAvailable = first.getValue();
                    if (amountAvailable <= 1) {
                        amount.remove(first.getKey());
                    } else {
                        first.setValue(amountAvailable - 1);
                    }
                    
                    OwnershipStatus originalStatus = first.getKey();
                    OwnershipStatus transformedStatus;
                    if (originalStatus.isOwned()) {
                        transformedStatus = OwnershipStatus.owned(originalStatus.getOwnerUuid().get());
                    } else if (originalStatus.isTimered()) {
                        transformedStatus = OwnershipStatus.empty();
                    } else {
                        transformedStatus = originalStatus;
                    }
                    
                    StableOrderingMap<OwnershipStatus, Integer> singleAmount = new StableOrderingMap<>(
                        OwnershipStatus.playerDropOrder(player.getUniqueId()),
                        OwnershipStatus::equals
                    );
                    singleAmount.put(transformedStatus, 1);
                    
                    // Signal waiting thread
                    getOrCreateQueue(player.getUniqueId()).offer(singleAmount);
                }
                break;
            }
            
            case DROP_ALL_SLOT:
            case DROP_ONE_SLOT: {
                final ItemStack dropped = clickedItemStack.asOne();
                final UUID playerId = player.getUniqueId();
                Bukkit.getScheduler().runTask(FairKeepInventoryPlugin.getInstance(), () -> {
                    for (var entry: table.syncItemLost(clickedInventory, dropped).entrySet()) {
                        Bukkit.getLogger().info("sending ownership for " + entry.getKey() + ": " + entry.getValue());
                        // Signal waiting thread
                        getOrCreateQueue(playerId).offer(entry.getValue());
                    }
                });
                break;
            }
            case MOVE_TO_OTHER_INVENTORY: {
                ItemStack stackToMove = new ItemStack(clickedItemStack);
                Inventory destination = null;
                if (top == clickedInventory) {
                    destination = bottom;
                } else if (bottom == clickedInventory) {
                    destination = top;
                } else {
                    // Bukkit.getLogger().info("top and bottom are both not clickedInventory");
                    return;
                }
                table.trackInventory(clickedInventory);
                table.trackInventory(destination);
                final Inventory dest = destination;
                Bukkit.getScheduler().runTask(
                    FairKeepInventoryPlugin.getInstance(),
                    () -> {
                        // Bukkit.getLogger().info("Moving " + stackToMove + " from " + InventoryId.from(clickedInventory) + " to " + InventoryId.from(dest));
                        // Bukkit.getLogger().info("clicked inventory: " + Arrays.asList(clickedInventory.getStorageContents()));
                        // Bukkit.getLogger().info("destination: " + Arrays.asList(dest.getStorageContents()));
                        table.syncItemTransfer(clickedInventory, dest, stackToMove);
                    }
                );
                break;
            }
            case HOTBAR_MOVE_AND_READD:
            case HOTBAR_SWAP:
                break;
            case CLONE_STACK:
                break;
            case COLLECT_TO_CURSOR:
                table.syncItemLost(clickedInventory, clickedItemStack);
                break;
            case UNKNOWN:
                break;
            case PICKUP_FROM_BUNDLE:
            case PICKUP_ALL_INTO_BUNDLE:
            case PICKUP_SOME_INTO_BUNDLE:
                break;
            case PLACE_FROM_BUNDLE:
            case PLACE_ALL_INTO_BUNDLE:
            case PLACE_SOME_INTO_BUNDLE:
                break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        // Bukkit.getLogger().info("");
        // Bukkit.getLogger().info("" + event.getEventName());
        // Bukkit.getLogger().info("inventory: " + InventoryId.from(event.getInventory()));
        // Bukkit.getLogger().info("clicked inventory: " + InventoryId.from(event.getClickedInventory()));
        // Bukkit.getLogger().info("recipe: " + event.getRecipe());
        // Bukkit.getLogger().info("result: " + event.getRecipe().getResult());
        // Bukkit.getLogger().info("");
        final Inventory craftingInventory = event.getInventory();
        final Inventory playerInventory = event.getWhoClicked().getInventory();
        final ItemStack craftingResult = event.getRecipe().getResult();
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> {
                table.syncItemGet(craftingInventory, craftingResult, Optional.empty());
                table.trackInventory(craftingInventory);
                table.syncItemGet(playerInventory, craftingResult, Optional.empty());
                table.trackInventory(playerInventory);
            }
        );
    }
}
