package com.fairkeepinventory;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.fairkeepinventory.model.OwnershipTable;

public class PlayerUseItem implements Listener {
    protected OwnershipTable table = OwnershipTable.getInstance();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        final Inventory inventory = event.getPlayer().getInventory();
        final ItemStack itemType = event.getItemInHand().asOne();
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> table.syncItemLost(inventory, itemType, 1)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemBreak(PlayerItemBreakEvent event) {
        final Inventory inventory = event.getPlayer().getInventory();
        final ItemStack itemType = event.getBrokenItem().asOne();
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> table.syncItemLost(inventory, itemType, 1)
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        final Inventory inventory = event.getPlayer().getInventory();
        final ItemStack itemType = event.getItem().asOne();
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> table.syncItemLost(inventory, itemType, 1)
        );
    }
}
