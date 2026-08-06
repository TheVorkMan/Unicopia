package com.minelittlepony.unicopia.network.track;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.lang.reflect.Method;

import com.minelittlepony.unicopia.network.Channel;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.Entity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.network.ServerPlayerEntity;



public class DataTrackerManager {
    private final Entity entity;
    private DynamicRegistryManager lookup;
    private Boolean isClient;
    private final List<DataTracker> trackers = new ObjectArrayList<>();
    private final List<ObjectTracker<?>> objectTrackers = new ObjectArrayList<>();
    private final List<PacketEmitter> packetEmitters = new ObjectArrayList<>();

    private DataTracker primaryTracker;

    public DataTrackerManager(Entity entity) {
        this.entity = entity;
        // entity.getWorld() can be null at construction time; resolve lazily instead.
        this.primaryTracker = checkoutTracker();
    }

    // Lazily resolves the registry lookup once the entity has a world.
    public DynamicRegistryManager getLookup() {
        if (lookup == null && entity.getWorld() != null) {
            lookup = entity.getWorld().getRegistryManager();
        }
        return lookup;
    }

    // Lazily resolves whether we're on the client once the entity has a world.
    public boolean isClient() {
        if (isClient == null) {
            if (entity.getWorld() == null) {
                return false;
            }
            isClient = entity.getWorld().isClient;
        }
        return isClient;
    }

    public synchronized void addPacketEmitter(PacketEmitter packetEmitter) {
        packetEmitters.add(packetEmitter);
    }

    public DataTracker getPrimaryTracker() {
        return primaryTracker;
    }

    public synchronized DataTracker checkoutTracker() {
        DataTracker tracker = new DataTracker(trackers.size());
        trackers.add(tracker);
        packetEmitters.add((sender, initial) -> {
            var update = initial ? tracker.getInitialPairs(getLookup()) : tracker.getDirtyPairs(getLookup());
            if (update.isPresent()) {
                Packet<?> packet = Channel.SERVER_TRACKED_ENTITY_DATA.toPacket(new MsgTrackedValues(
                        entity.getId(),
                        Optional.empty(),
                        update
                ));
                sendPacketSafely(sender, packet);
            }
        });
        return tracker;
    }

    public synchronized <T extends TrackableObject<T>> ObjectTracker<T> checkoutTracker(Supplier<T> objFunction) {
        ObjectTracker<T> tracker = new ObjectTracker<>(objectTrackers.size(), objFunction);
        objectTrackers.add(tracker);
        packetEmitters.add((sender, initial) -> {
            var update = initial ? tracker.getInitialPairs(getLookup()) : tracker.getDirtyPairs(getLookup());
            if (update.isPresent()) {
                Packet<?> packet = Channel.SERVER_TRACKED_ENTITY_DATA.toPacket(new MsgTrackedValues(
                        entity.getId(),
                        update,
                        Optional.empty()
                ));
                sendPacketSafely(sender, packet);
            }
        });
        return tracker;
    }

    public void tick(Consumer<Packet<?>> sender) {
        synchronized (this) {
            for (var emitter : packetEmitters) {
                emitter.sendPackets(sender, false);
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public synchronized void copyTo(DataTrackerManager destination) {
        for (int i = 0; i < trackers.size(); i++) {
            trackers.get(i).copyTo(i >= destination.trackers.size() ? destination.checkoutTracker() : destination.trackers.get(i));
        }
        for (int i = 0; i < objectTrackers.size(); i++) {
            ((ObjectTracker)objectTrackers.get(i)).copyTo(destination.objectTrackers.get(i));
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public synchronized void sendInitial(ServerPlayerEntity player, Consumer<Packet<ClientPlayPacketListener>> sender) {
        synchronized (this) {
            for (var emitter : packetEmitters) {
                emitter.sendPackets((Consumer)sender, true);
            }
        }
    }

    synchronized void load(MsgTrackedValues packet) {
        packet.updatedTrackers().ifPresent(update -> {
            DataTracker tracker = trackers.get(update.id());
            if (tracker != null) {
                tracker.load(update, getLookup());
            }
        });
        packet.updatedObjects().ifPresent(update -> {
            ObjectTracker<?> tracker = objectTrackers.get(update.id());
            if (tracker != null) {
                tracker.load(update, getLookup());
            }
        });
    }

    public interface PacketEmitter {
        void sendPackets(Consumer<Packet<?>> consumer, boolean initial);
    }

    // Some NeoForge/Connector packet acceptors (e.g. PacketAndPayloadAcceptor) implement
    // Consumer<T> for a narrowed T without a generated accept(Object) bridge method.
    // `instanceof Consumer` still succeeds since the class implements the interface,
    // but dispatching accept() on the erased signature throws AbstractMethodError.
    // Falling back to whichever compatible send method the instance actually exposes
    // avoids the crash without a compile-time dependency on Connector/NeoForge internals.
    private static final Map<Class<?>, Method> FALLBACK_METHODS = new ConcurrentHashMap<>();

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void sendPacketSafely(Consumer sender, Packet<?> packet) {
        if (sender == null) {
            return;
        }
        try {
            sender.accept(packet);
        } catch (AbstractMethodError e) {
            Method fallback = FALLBACK_METHODS.computeIfAbsent(sender.getClass(), DataTrackerManager::findFallbackMethod);
            if (fallback == null) {
                throw new RuntimeException("No accept/sendPacket method found on " + sender.getClass(), e);
            }
            try {
                fallback.invoke(sender, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException("Failed to send packet via fallback method on " + sender.getClass(), ex);
            }
        }
    }

    private static Method findFallbackMethod(Class<?> clazz) {
        for (Method m : clazz.getMethods()) {
            if ((m.getName().equals("accept") || m.getName().equals("sendPacket")) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
