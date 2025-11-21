package com.fairkeepinventory;

import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import com.fairkeepinventory.model.OwnershipTable;

public class ItemDropOnDeath implements Listener {
    protected OwnershipTable table = OwnershipTable.getInstance();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.setKeepInventory(true);
        event.getDrops().clear();

        Player player = event.getEntity();

        Map<ItemStack, Integer> removedItems = table.removeItems(player, status ->
            !status.isOwnedBy(player.getUniqueId())
        );

        for (Map.Entry<ItemStack, Integer> entry: removedItems.entrySet()) {
            ItemStack stack = new ItemStack(entry.getKey());
            stack.setAmount(entry.getValue());
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }
}
