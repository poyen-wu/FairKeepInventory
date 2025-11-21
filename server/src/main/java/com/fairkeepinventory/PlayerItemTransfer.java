package com.fairkeepinventory;

import java.util.Arrays;
import java.util.Optional;

import org.bukkit.Bukkit;
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Bukkit.getScheduler().runTaskLater(FairKeepInventoryPlugin.getInstance(), () -> {
            Bukkit.getLogger().info("");
            Bukkit.getLogger().info("" + event.getEventName());
            Bukkit.getLogger().info("Item entity: " + event.getItemDrop().getUniqueId());
            table.InstantiatePlayerDroppedItems(event.getPlayer().getUniqueId(), event.getItemDrop());
            Bukkit.getLogger().info("owner: " + table.getItemEntityOwner(event.getItemDrop().getUniqueId()));
            Bukkit.getLogger().info("");
        }, 2L);
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
        Bukkit.getScheduler().runTaskLater(FairKeepInventoryPlugin.getInstance(), () -> table.unsetItemEntityOwner(event.getEntity().getUniqueId()), 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        Bukkit.getLogger().info("");
        Bukkit.getLogger().info("" + event.getEventName());
        Bukkit.getLogger().info("");
        Entity entity = event.getEntity();
        Item itemEntity = event.getItem();
        ItemStack item = itemEntity.getItemStack().asOne();
        Inventory inventory = InventoryId.from(entity).getInventory();
        table.trackInventory(inventory);
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> {
                Bukkit.getLogger().info("Item entity: " + itemEntity.getUniqueId());
                Bukkit.getLogger().info("");
                var status = table.getItemEntityOwner(itemEntity.getUniqueId());
                table.unsetItemEntityOwner(itemEntity.getUniqueId());
                table.syncItemGet(inventory, item, status);
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
        }, 500L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Bukkit.getLogger().info("");
        Bukkit.getLogger().info("" + event.getEventName());
        Bukkit.getLogger().info("");
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
                Bukkit.getLogger().info("Before pick up: " + Arrays.asList(clickedInventory.getContents()));
                Bukkit.getScheduler().runTask(
                    FairKeepInventoryPlugin.getInstance(),
                    () -> {
                        Bukkit.getLogger().info("After picked up: " + Arrays.asList(clickedInventory.getContents()));
                        Bukkit.getLogger().info("Picked up stack: " + pickedUp);
                        var status = table.syncItemLost(clickedInventory, pickedUp).get(pickedUp.asOne());
                        for (var each: status.entrySet()) {
                            Bukkit.getLogger().info(
                                "owner: " + (each.getKey().isOwned() ? each.getKey().getOwnerUuid() : "") +
                                "timer: " + (each.getKey().isTimered() ? each.getKey().getTimer().get().getRemainingSeconds() : "") +
                                "amount: " + each.getValue()
                            );
                        }
                        table.setCursor(player.getUniqueId(), pickedUp.asOne(), status);
                    });
                break;
            }
            case PLACE_ALL:
            case PLACE_SOME:
            case PLACE_ONE: {
                table.trackInventory(event.getClickedInventory());
                Bukkit.getScheduler().runTask(
                    FairKeepInventoryPlugin.getInstance(),
                    () -> {
                        var status = table.takeCursor(player.getUniqueId());
                        table.syncItemGet(clickedInventory, status.getItemType(), status.getAmount());
                    });
                break;
            }
            case SWAP_WITH_CURSOR: {
                Bukkit.getLogger().info("SWAP_WITH_CURSOR");
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
                Bukkit.getLogger().info("DROP_ALL_CURSOR");
                var status = table.takeCursor(player.getUniqueId());
                var amount = status.getAmount();
                table.setPlayerDroppedItemOwner(player.getUniqueId(), status.getItemType(), amount);
                break;
            }
            case DROP_ONE_CURSOR: {
                Bukkit.getLogger().info("DROP_ONE_CURSOR");
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
                    StableOrderingMap<OwnershipStatus, Integer> singleAmount = new StableOrderingMap<>(
                        OwnershipStatus.playerTakeOrder(player.getUniqueId()),
                        OwnershipStatus::equals
                    );
                    singleAmount.put(first.getKey(), 1);
                    table.setPlayerDroppedItemOwner(player.getUniqueId(), status.getItemType(), singleAmount);
                }
                break;
            }
            case DROP_ALL_SLOT:
            case DROP_ONE_SLOT: {
                final ItemStack dropped = clickedItemStack.asOne();
                Bukkit.getScheduler().runTask(FairKeepInventoryPlugin.getInstance(), () -> {
                    Bukkit.getLogger().info("");
                    Bukkit.getLogger().info("DROP_ONE_SLOT");
                    for (var entry: table.syncItemLost(clickedInventory, dropped).entrySet()) {
                        Bukkit.getLogger().info("item: " + entry.getKey());
                        for (var status: entry.getValue().entrySet()) {
                            Bukkit.getLogger().info("owner: " + status);
                        }
                        table.setPlayerDroppedItemOwner(player.getUniqueId(), entry.getKey(), entry.getValue());
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
                    Bukkit.getLogger().info("top and bottom are both not clickedInventory");
                    return;
                }
                table.trackInventory(clickedInventory);
                table.trackInventory(destination);
                final Inventory dest = destination;
                Bukkit.getScheduler().runTask(
                    FairKeepInventoryPlugin.getInstance(),
                    () -> {
                        Bukkit.getLogger().info("Moving " + stackToMove + " from " + InventoryId.from(clickedInventory) + " to " + InventoryId.from(dest));
                        Bukkit.getLogger().info("clicked inventory: " + Arrays.asList(clickedInventory.getStorageContents()));
                        Bukkit.getLogger().info("destination: " + Arrays.asList(dest.getStorageContents()));
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
        Bukkit.getLogger().info("");
        Bukkit.getLogger().info("" + event.getEventName());
        Bukkit.getLogger().info("inventory: " + InventoryId.from(event.getInventory()));
        Bukkit.getLogger().info("clicked inventory: " + InventoryId.from(event.getClickedInventory()));
        Bukkit.getLogger().info("recipe: " + event.getRecipe());
        Bukkit.getLogger().info("result: " + event.getRecipe().getResult());
        Bukkit.getLogger().info("");
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
