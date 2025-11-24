package com.fairkeepinventory;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        final ItemStack item = event.getItem().asOne();
        final int damage = event.getDamage();
        final Inventory inventory = event.getPlayer().getInventory();
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> {
                ItemStack damagedItem = item.asOne();
                Damageable meta = (Damageable) damagedItem.getItemMeta();
                int currentDamage = meta.getDamage() + damage;
                meta.setDamage(currentDamage);
                damagedItem.setItemMeta((ItemMeta) meta);
                table.syncItemUpdate(inventory, item, damagedItem);
            }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemMend(PlayerItemMendEvent event) {
        final ItemStack item = event.getItem().asOne();
        final int mendAmount = event.getRepairAmount();
        final Inventory inventory = event.getPlayer().getInventory();
        Bukkit.getScheduler().runTask(
            FairKeepInventoryPlugin.getInstance(),
            () -> {
                ItemStack mendedItem = item.asOne();
                Damageable meta = (Damageable) mendedItem.getItemMeta();
                int currentDamage = Math.max(0, meta.getDamage() - mendAmount);
                meta.setDamage(currentDamage);
                mendedItem.setItemMeta((ItemMeta) meta);
                table.syncItemUpdate(inventory, item, mendedItem);
            }
        );
    }
}
