package com.fairkeepinventory;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class OwnershipStatus {
    public static class Timer {
        private final UUID playerId;
        private final int remainingSeconds;

        protected Timer(UUID playerId, int remainingSeconds) {
            this.playerId = playerId;
            this.remainingSeconds = remainingSeconds;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public int getRemainingSeconds() {
            return this.remainingSeconds;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Timer)) return false;
            Timer other = (Timer) o;
            return remainingSeconds == other.remainingSeconds
                && Objects.equals(playerId, other.playerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, remainingSeconds);
        }
    }

    public static final int TIMER_INIT_SECONDS = 600;
    private final Optional<UUID> owner;
    private final Optional<Timer> timer;

    protected OwnershipStatus(Optional<UUID> owner, Optional<Timer> timer) {
        this.owner = owner;
        this.timer = timer;
    }

    public static OwnershipStatus timered(UUID playerId, int remainingSeconds) {
        return new OwnershipStatus(
            Optional.empty(),
            Optional.of(new Timer(playerId, Math.max(remainingSeconds, 1)))
        );
    }

    public static OwnershipStatus timered(UUID playerId) {
        return timered(playerId, TIMER_INIT_SECONDS);
    }

    public static OwnershipStatus empty() {
        return new OwnershipStatus(
            Optional.empty(),
            Optional.empty()
        );
    }

    public static OwnershipStatus owned(UUID playerId) {
        return new OwnershipStatus(
            Optional.of(playerId),
            Optional.empty()
        );
    }

    public static OwnershipStatus claimingOwned(UUID owner, UUID claimer, int remainingSeconds) {
        return new OwnershipStatus(
            Optional.of(owner),
            Optional.of(new Timer(claimer, Math.max(remainingSeconds, 1)))
        );
    }

    public static OwnershipStatus claimingOwned(UUID owner, UUID claimer) {
        return claimingOwned(owner, claimer, TIMER_INIT_SECONDS);
    }

    public Optional<UUID> getOwnerUuid() {
        return owner;
    }

    public Optional<Timer> getTimer() {
        return timer;
    }

    public OwnershipStatus tickTimer(int seconds) {
        if (!this.isTimered() || seconds == 0) {
            return this;
        }

        Timer timer = this.timer.get();
        if (seconds >= timer.remainingSeconds) {
            return OwnershipStatus.owned(timer.playerId);
        } else if (this.isOwned()) {
            return OwnershipStatus.claimingOwned(
                this.getOwnerUuid().get(), timer.playerId,
                timer.remainingSeconds - seconds
            );
        } else {
            return OwnershipStatus.timered(
                timer.playerId,
                timer.remainingSeconds - seconds
            );
        }
    }

    public boolean isEmpty() {
        return !this.isOwned() && !this.isTimered();
    }

    public boolean isNew() {
        return !this.isOwned() && this.isTimered() && timer.get().remainingSeconds == TIMER_INIT_SECONDS;
    }

    public boolean isOwned() {
        return owner.isPresent();
    }

    public boolean isTimered() {
        return timer.isPresent();
    }

    public static Comparator<OwnershipStatus> playerOrder(UUID playerId) {
        return Comparator
            // Drop other's items first, then own
            .comparingInt((OwnershipStatus status) ->
                status.getOwnerUuid()
                    .map(owner -> owner.equals(playerId) ? 2 : 1)
                    .orElse(0)
            )
            // Drop items with empty/other's/longer timers first
            .thenComparing(
                (OwnershipStatus status) -> status.getTimer().orElse(null),
                Comparator.nullsFirst(
                    Comparator
                        .comparingInt((Timer timer) ->
                            timer.playerId.equals(playerId) ? 2 : 1
                        )
                        .thenComparing(Comparator.comparingInt(Timer::getRemainingSeconds).reversed())
                )
            );
    }

    public static Comparator<OwnershipStatus> sharedOrder() {
        return Comparator
            // Owned vs unowned first
            .comparing(
                OwnershipStatus::getOwnerUuid,
                Comparator.comparingInt(owner -> owner.isPresent() ? 2 : 1)
            )
            // Then by timer presence / remaining time
            .thenComparing(
                (OwnershipStatus status) -> status.getTimer().orElse(null),
                Comparator.nullsFirst(
                    Comparator.comparingInt(Timer::getRemainingSeconds).reversed()
                )
            );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OwnershipStatus)) return false;
        OwnershipStatus other = (OwnershipStatus) o;
        return Objects.equals(owner, other.owner)
            && Objects.equals(timer, other.timer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, timer);
    }
}
