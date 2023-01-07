package cam72cam.immersiverailroading.entity.physics;

import cam72cam.immersiverailroading.ImmersiveRailroading;
import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock;
import cam72cam.immersiverailroading.entity.physics.chrono.ChronoState;
import cam72cam.immersiverailroading.net.MRSSyncPacket;
import cam72cam.immersiverailroading.physics.TickPos;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.world.World;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Bugs:
 * Turn tables
 * Waiting for chunk loading for long trains
 *
 * Todo:
 * Integrate block breaking forces (including snow clearing)
 * Optimizations
 *
 */
public class Simulation {

    public static void simulate(World world) {
        // 100KM/h ~= 28m/s which means non-loaded stationary stock may be phased through at that speed
        // I'm OK with that for now
        // We might want to chunk-load ahead of the train just to be safe?

        if (world.getTicks() % 5 != 0) {
            // Only re-check every 5 ticks
            return;
        }

        if (ChronoState.getState(world).getTickID() < 20 * 2) {
            // Wait for at least 2 seconds before starting simulation (for stock to load)
            return;
        }

        List<EntityCoupleableRollingStock> allStock = world.getEntities(EntityCoupleableRollingStock.class);
        if (allStock.isEmpty()) {
            return;
        }

        int pass = (int) ChronoState.getState(world).getTickID();

        List<Map<UUID, SimulationState>> stateMaps = new ArrayList<>();
        List<Vec3i> blocksAlreadyBroken = new ArrayList<>();

        for (int i = 0; i < 40; i++) {
            stateMaps.add(new HashMap<>());
        }

        boolean anyStartedDirty = false;

        for (EntityCoupleableRollingStock entity : allStock) {
            SimulationState current = entity.getCurrentState();
            if (current == null) {
                // Newly placed
                stateMaps.get(0).put(entity.getUUID(), new SimulationState(entity));
                anyStartedDirty = true;
            } else {
                current.update(entity);
                if (current.dirty) {
                    // Changed since last simulation
                    stateMaps.get(0).put(entity.getUUID(), current);
                    anyStartedDirty = true;
                } else {
                    // Copy from previous simulation
                    int toCopy = Math.min(30, entity.states.size());
                    for (int i = 0; i < toCopy; i++) {
                        stateMaps.get(i).put(entity.getUUID(), entity.states.get(i));
                    }
                }
            }
        }

        double maxCouplerDist = 4;

        for (int i = 0; i < stateMaps.size(); i++) {
            Map<UUID, SimulationState> stateMap = stateMaps.get(i);

            List<SimulationState> states = new ArrayList<>(stateMap.values());

            // Decouple / fix coupler positions
            for (SimulationState state : states) {
                for (boolean isMyCouplerFront : new boolean[]{true, false}) {
                    UUID myID = state.config.id;
                    UUID otherID = isMyCouplerFront ? state.interactingFront : state.interactingRear;
                    Vec3d myCouplerPos = isMyCouplerFront ? state.couplerPositionFront : state.couplerPositionRear;
                    String myCouplerLabel = isMyCouplerFront ? "Front" : "Rear";

                    if (otherID == null) {
                        // No existing coupler, nothing to check
                        continue;
                    }

                    SimulationState other = stateMap.get(otherID);
                    if (other == null) {
                        // Likely due to chunk loading?
                        ImmersiveRailroading.debug("%s-%s: Unable to find coupled stock for %s (%s) -> %s!",
                                pass, state.tickID, myID, myCouplerLabel, otherID);

                        state.dirty = true;
                        if (isMyCouplerFront) {
                            state.interactingFront = null;
                        } else {
                            state.interactingRear = null;
                        }
                        continue;
                    }

                    boolean isOtherCouplerFront;
                    if (myID.equals(other.interactingFront)) {
                        isOtherCouplerFront = true;
                    } else if (myID.equals(other.interactingRear)) {
                        isOtherCouplerFront = false;
                    } else {
                        /*
                        This can happen when a piece of stock is marked dirty (discard existing states ex: throttle/brake)
                        and a piece of stock it couples with in the future states has not been marked dirty.  The dirty
                        stock is starting from 0, while the one it has future coupled to in a previous pass (that's english right?)
                        does not know until this re-check that it has desync'd at this point and must generate new states
                        from here on out in this pass.
                         */
                        ImmersiveRailroading.debug("%s-%s: Mismatched coupler states: %s (%s) -> %s (%s, %s)",
                                pass, state.tickID,
                                myID, myCouplerLabel,
                                otherID, other.interactingFront, other.interactingRear);
                        state.interactingFront = null;
                        state.dirty = true;
                        other.dirty = true;
                        continue;
                    }

                    Vec3d otherCouplerPos = isOtherCouplerFront ? other.couplerPositionFront : other.couplerPositionRear;
                    String otherCouplerLabel = isOtherCouplerFront ? "Front" : "Rear";

                    double maxCouplerDistScaled = maxCouplerDist * state.config.gauge.scale();
                    if (myCouplerPos.distanceToSquared(otherCouplerPos) > maxCouplerDistScaled * maxCouplerDistScaled) {
                        ImmersiveRailroading.debug("%s-%s: Coupler snapping due to distance: %s (%s) -> %s (%s)",
                                pass, state.tickID,
                                myID, myCouplerLabel,
                                otherID, otherCouplerLabel);
                        state.dirty = true;
                        other.dirty = true;

                        if (isMyCouplerFront) {
                            state.interactingFront = null;
                        } else {
                            state.interactingRear = null;
                        }

                        if (isOtherCouplerFront) {
                            other.interactingFront = null;
                        } else {
                            other.interactingRear = null;
                        }
                    }
                }
            }


            // check for potential couplings and collisions
            for (int sai = 0; sai < states.size()-1; sai++) {
                SimulationState stateA = states.get(sai);
                if (stateA.interactingFront != null && stateA.interactingRear != null) {
                    // There's stock in front and behind, can't really hit any other stock here
                    continue;
                }
                for (int sbi = sai+1; sbi < states.size(); sbi++) {
                    SimulationState stateB = states.get(sbi);
                    if (stateB.interactingFront != null && stateB.interactingRear != null) {
                        // There's stock in front and behind, can't really hit any other stock here
                        continue;
                    }

                    if (stateA.config.gauge != stateB.config.gauge) {
                        // Same gauge required
                        continue;
                    }

                    double centerDist = stateA.config.length + stateB.config.length;
                    if (stateA.position.distanceToSquared(stateB.position) > centerDist * centerDist) {
                        // Too far to reasonably couple
                        continue;
                    }

                    if (!stateA.bounds.intersects(stateB.bounds)) {
                        // Not close enough to couple
                        continue;
                    }

                    if (stateB.config.id.equals(stateA.interactingFront) || stateB.config.id.equals(stateA.interactingRear)) {
                        // Already coupled
                        continue;
                    }
                    if (stateA.config.id.equals(stateB.interactingFront) || stateA.config.id.equals(stateB.interactingRear)) {
                        // Already coupled (double safe check)
                        continue;
                    }

                    // At this point the stock are colliding / overlapping and we need to do something about it

                    /*
                     * 1. |-----a-----| |-----b-----|
                     * 2. |-----a---|=|----b-----|
                     * 3. |---|=a====b|-----|
                     * Keep in mind that we want to make sure that our other coupler might be a better fit
                     */

                    // the coupler to target is whichever one the other's center is closest to
                    boolean targetACouplerFront =
                            stateA.couplerPositionFront.distanceToSquared(stateB.position) <
                            stateA.couplerPositionRear.distanceToSquared(stateB.position);
                    boolean targetBCouplerFront =
                            stateB.couplerPositionFront.distanceToSquared(stateA.position) <
                            stateB.couplerPositionRear.distanceToSquared(stateA.position);

                    // Best coupler is already coupled to something
                    if ((targetACouplerFront ? stateA.interactingFront : stateA.interactingRear) != null) {
                        continue;
                    }
                    if ((targetBCouplerFront ? stateB.interactingFront : stateB.interactingRear) != null) {
                        continue;
                    }

                    // Since bounding boxes can overlap across different tracks (think parallel curves) we need to do
                    // a more fine-grained check here
                    Vec3d couplerPosA = targetACouplerFront ? stateA.couplerPositionFront : stateA.couplerPositionRear;
                    // Move coupler pos up to inside the BB (it's at track level by default)
                    // This could be optimized further, but it's an infrequent calculation
                    couplerPosA = couplerPosA.add(0, stateA.bounds.max().subtract(stateA.bounds.min()).y/2, 0);
                    if (!stateB.bounds.contains(couplerPosA)) {
                        // Not actually on the same track, just a BB collision and can be ignored
                        continue;
                    }

                    stateA.dirty = true;
                    stateB.dirty = true;
                    ImmersiveRailroading.debug("%s-%s: Coupling %s (%s) to %s (%s)",
                            pass, stateA.tickID,
                            stateA.config.id, targetACouplerFront ? "Front" : "Rear",
                            stateB.config.id, targetBCouplerFront ? "Front" : "Rear");

                    // Ok, we are clear to proceed!
                    if (targetACouplerFront) {
                        stateA.interactingFront = stateB.config.id;
                    } else {
                        stateA.interactingRear = stateB.config.id;
                    }
                    if (targetBCouplerFront) {
                        stateB.interactingFront = stateA.config.id;
                    } else {
                        stateB.interactingRear = stateA.config.id;
                    }
                }
            }

            // calculate new velocities
            if (i + 1 < stateMaps.size()) {
                stateMaps.get(i+1).putAll(Consist.iterate(stateMap, blocksAlreadyBroken));
            }
        }

        // Apply new states
        for (EntityCoupleableRollingStock stock : allStock) {
            stock.states = stateMaps.stream().map(m -> m.get(stock.getUUID())).filter(Objects::nonNull).collect(Collectors.toList());
            for (SimulationState state : stock.states) {
                state.dirty = false;
            }
            stock.positions = stock.states.stream().map(TickPos::new).collect(Collectors.toList());
            if (world.getTicks() % 20 == 0 || anyStartedDirty) {
                new MRSSyncPacket(stock, stock.positions).sendToObserving(stock);
            }
        }
    }
}
