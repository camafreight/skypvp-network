package network.skypvp.extraction.engine;

import java.util.List;
import java.util.UUID;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.gamemode.api.BreachCapacityProvider;
import network.skypvp.shared.BreachInstanceSnapshot;

public final class BreachCapacityReporter implements BreachCapacityProvider {
   private final BreachEngine breachEngine;
   private final BreachConfigService configService;

   public BreachCapacityReporter(BreachEngine breachEngine, BreachConfigService configService) {
      this.breachEngine = breachEngine;
      this.configService = configService;
   }

   @Override
   public int openBreachSlots() {
      return this.breachEngine.worldPool().capacityRemaining();
   }

   @Override
   public int activeBreaches() {
      return this.breachEngine.activeInstances().size();
   }

   @Override
   public int queuedPlayers() {
      return this.breachEngine.queueService().queuedCount();
   }

   @Override
   public int maxPlayersPerPod() {
      return this.configService.maxPlayersPerPod();
   }

   @Override
   public List<BreachInstanceSnapshot> breachInstanceCatalog() {
      return this.breachEngine.activeInstances().stream()
         .filter(instance -> instance.state() == BreachState.ACTIVE)
         .map(instance -> new BreachInstanceSnapshot(
            instance.instanceId(),
            instance.mapMeta().mapId(),
            instance.openSlots(),
            instance.mapMeta().maxPlayers(),
            instance.canAcceptPlayers(1),
            instance.activePartyIds().stream().map(UUID::toString).toList()
         ))
         .toList();
   }
}
