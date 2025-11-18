package com.fairkeepinventory;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.Location;

public abstract class InventoryId {
    protected static abstract class Id {
        @Override
        public abstract boolean equals(Object o);

        @Override
        public abstract int hashCode();
    }

    public static class EntityInventoryId extends InventoryId {
        protected static class Id extends InventoryId.Id {
            final UUID uuid;

            public Id(UUID uuid) {
                this.uuid = uuid;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Id id = (Id) o;
                return Objects.equals(uuid, id.uuid);
            }

            @Override
            public int hashCode() {
                return Objects.hash(uuid);
            }
        }

        protected EntityInventoryId(Id id) {
            super(id);
        }

        @Override
        public String toString() {
            return "entity:" + ((Id) super.id).uuid.toString();
        }
    }

    public static class PlayerInventoryId extends EntityInventoryId {
        protected static class Id extends EntityInventoryId.Id {
            protected final InventoryType inventoryType;

            public Id(UUID uuid, InventoryType inventoryType) {
                super(uuid);
                this.inventoryType = inventoryType;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Id id = (Id) o;
                return Objects.equals(uuid, id.uuid)
                    && inventoryType == id.inventoryType;
            }

            @Override
            public int hashCode() {
                return Objects.hash(uuid, inventoryType);
            }
        }

        protected PlayerInventoryId(Id id) {
            super(id);
        }

        @Override
        public String toString() {
            Id id = ((Id) super.id);
            return id.inventoryType.name() + ":" + id.uuid.toString();
        }
    }

    public static class BlockInventoryId extends InventoryId {
        protected static class Id extends InventoryId.Id {
            protected final Location location;

            public Id(Location location) {
                this.location = location;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Id id = (Id) o;
                return Objects.equals(location, id.location);
            }

            @Override
            public int hashCode() {
                return Objects.hash(location);
            }
        }

        protected BlockInventoryId(Id id) {
            super(id);
        }

        public Location getLocation() {
            return ((Id) super.id).location;
        }

        @Override
        public String toString() {
            return "block:" + getLocation().toString();
        }
    }

    public static class VirtualInventoryId extends InventoryId {
        protected static class Id extends InventoryId.Id {
            @Override
            public boolean equals(Object o) {
                return o != null && getClass() == o.getClass();
            }

            @Override
            public int hashCode() {
                return Id.class.hashCode();
            }
        }

        protected VirtualInventoryId(Id id) {
            super(id);
        }

        @Override
        public String toString() {
            return "";
        }
    }

    protected final Id id;

    protected InventoryId(Id id) {
        this.id = id;
    }

    public static InventoryId from(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();

        if (holder instanceof Player player) {
            return new PlayerInventoryId(playerId(player, inventory.getType()));
        }

        if (holder instanceof Entity entity) {
            return new EntityInventoryId(entityId(entity));
        }

        // Double chests do not implement BlockState
        if (holder instanceof DoubleChest doubleChest) {
            Block leftBlock = ((Chest) doubleChest.getLeftSide()).getBlock();
            return new BlockInventoryId(blockId(leftBlock));
        }

        if (holder instanceof BlockState blockState) {
            return new BlockInventoryId(blockId(blockState.getBlock()));
        }

        return new VirtualInventoryId(new VirtualInventoryId.Id());
    }

    protected static PlayerInventoryId.Id playerId(Player player, InventoryType inventoryType) {
        return new PlayerInventoryId.Id(player.getUniqueId(), inventoryType);
    }

    protected static EntityInventoryId.Id entityId(Entity entity) {
        return new EntityInventoryId.Id(entity.getUniqueId());
    }

    protected static BlockInventoryId.Id blockId(Block block) {
        return new BlockInventoryId.Id(block.getLocation());
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InventoryId that = (InventoryId) o;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), id);
    }

    @Override
    public abstract String toString();
}
