package com.fairkeepinventory.model;

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
import org.bukkit.Bukkit;
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

            public UUID getUuid() {
                return this.uuid;
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

        public UUID getUuid() {
            return ((Id) super.id).uuid;
        }

        @Override
        public Inventory getInventory() {
            Entity self = Bukkit.getEntity(this.getUuid());
            if (self != null && self instanceof InventoryHolder holder) {
                return holder.getInventory();
            }
            return null;
        }

        @Override
        public String toString() {
            return "entity:" + ((Id) super.id).uuid.toString();
        }

        @Override
        public String serialize() {
            Id id = (Id) super.id;
            // ENTITY|UUID
            return "ENTITY|" + id.uuid;
        }

        public static EntityInventoryId deserialize(String[] parts) {
            // parts: [ "ENTITY", "UUID" ]
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid ENTITY InventoryId: " + String.join("|", parts));
            }
            UUID uuid = UUID.fromString(parts[1]);
            return new EntityInventoryId(new Id(uuid));
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

        public InventoryType getInventoryType() {
            return ((Id) super.id).inventoryType;
        }

        public UUID getPlayerId() {
            return this.getUuid();
        }

        @Override
        public Inventory getInventory() {
            Player self = Bukkit.getPlayer(this.getUuid());
            if (self != null) {
                switch (this.getInventoryType()) {
                    case PLAYER:
                        return self.getInventory();
                    case ENDER_CHEST:
                        return self.getEnderChest();
                    default:
                }
            }
            return null;
        }

        @Override
        public String toString() {
            Id id = ((Id) super.id);
            return id.inventoryType.name() + ":" + id.uuid.toString();
        }

        @Override
        public String serialize() {
            Id id = (Id) super.id;
            // PLAYER|INVENTORY_TYPE|UUID
            return "PLAYER|" + id.inventoryType.name() + "|" + id.uuid;
        }

        public static PlayerInventoryId deserialize(String[] parts) {
            // parts: [ "PLAYER", "INVENTORY_TYPE", "UUID" ]
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid PLAYER InventoryId: " + String.join("|", parts));
            }
            InventoryType type = InventoryType.valueOf(parts[1]);
            UUID uuid = UUID.fromString(parts[2]);
            return new PlayerInventoryId(new Id(uuid, type));
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
        public Inventory getInventory() {
            BlockState self = this.getLocation().getBlock().getState();
            if (self instanceof InventoryHolder holder) {
                return holder.getInventory();
            }
            return null;
        }

        @Override
        public String toString() {
            return "block:" + getLocation().toString();
        }

        @Override
        public String serialize() {
            Location loc = getLocation();
            if (loc == null || loc.getWorld() == null) {
                throw new IllegalStateException("BlockInventoryId has no world/location");
            }

            // BLOCK|WORLD_UUID|x|y|z
            return "BLOCK|" +
                    loc.getWorld().getUID() + "|" +
                    loc.getBlockX() + "|" +
                    loc.getBlockY() + "|" +
                    loc.getBlockZ();
        }

        public static BlockInventoryId deserialize(String[] parts) {
            // parts: [ "BLOCK", "WORLD_UUID", "x", "y", "z" ]
            if (parts.length != 5) {
                throw new IllegalArgumentException("Invalid BLOCK InventoryId: " + String.join("|", parts));
            }

            UUID worldId = UUID.fromString(parts[1]);
            int x = Integer.parseInt(parts[2]);
            int y = Integer.parseInt(parts[3]);
            int z = Integer.parseInt(parts[4]);

            org.bukkit.World world = Bukkit.getWorld(worldId);
            if (world == null) {
                throw new IllegalStateException("World not found for UUID " + worldId);
            }

            Location loc = new Location(world, x, y, z);
            return new BlockInventoryId(new Id(loc));
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
        public Inventory getInventory() {
            return null;
        }

        @Override
        public String toString() {
            return "";
        }

        @Override
        public String serialize() {
            // VIRTUAL
            return "VIRTUAL";
        }

        public static VirtualInventoryId deserialize(String[] parts) {
            // parts: [ "VIRTUAL" ]
            if (parts.length != 1) {
                throw new IllegalArgumentException("Invalid VIRTUAL InventoryId: " + String.join("|", parts));
            }
            return new VirtualInventoryId(new Id());
        }
    }

    protected final Id id;

    protected InventoryId(Id id) {
        this.id = id;
    }

    public static InventoryId from(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();

        // Double chests do not implement BlockState
        if (holder instanceof DoubleChest doubleChest) {
            Block leftBlock = ((Chest) doubleChest.getLeftSide()).getBlock();
            return new BlockInventoryId(blockId(leftBlock));
        }

        if (holder instanceof BlockState blockState) {
            return new BlockInventoryId(blockId(blockState.getBlock()));
        }

        if (holder instanceof Player player) {
            return new PlayerInventoryId(playerId(player, inventory.getType()));
        }

        if (holder instanceof Entity entity) {
            return new EntityInventoryId(entityId(entity));
        }

        switch (inventory.getType()) {
            case ANVIL:
            case BEACON:
            case GRINDSTONE:
            case STONECUTTER: {
                return new PlayerInventoryId(playerId(
                    Bukkit.getPlayer(inventory.getViewers().get(0).getUniqueId()),
                    inventory.getType()
                ));
            }
            default:
        }

        return new VirtualInventoryId(new VirtualInventoryId.Id());
    }

    public static InventoryId from(Entity entity) {
        if (entity instanceof Player player) {
            return new PlayerInventoryId(playerId(player, InventoryType.PLAYER));
        }
        return new EntityInventoryId(entityId(entity));
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

    public abstract Inventory getInventory();

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

    public abstract String serialize();

    public static InventoryId deserialize(String data) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("InventoryId string is null/empty");
        }

        // Simple format: KIND|...
        String[] parts = data.split("\\|");
        String kind = parts[0];

        switch (kind) {
            case "PLAYER":
                return PlayerInventoryId.deserialize(parts);
            case "ENTITY":
                return EntityInventoryId.deserialize(parts);
            case "BLOCK":
                return BlockInventoryId.deserialize(parts);
            case "VIRTUAL":
                return VirtualInventoryId.deserialize(parts);
            default:
                throw new IllegalArgumentException("Unknown InventoryId kind: " + kind);
        }
    }
}
