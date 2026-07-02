package network.skypvp.extraction.engine;

import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.paper.gamemode.api.BreachCapacityProvider;

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
}
