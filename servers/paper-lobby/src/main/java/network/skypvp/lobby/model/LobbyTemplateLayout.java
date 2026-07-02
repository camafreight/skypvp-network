package network.skypvp.lobby.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import network.skypvp.paper.model.WorldPoint;

public final class LobbyTemplateLayout {
   public WorldPoint spawn = new WorldPoint("world", 0.5, 100.0, 0.5, 0.0F, 0.0F);
   public Map<String, WorldPoint> navigationPoints = new LinkedHashMap<>();
   public List<LobbyNpcDefinition> npcs = new ArrayList<>();
   public List<LobbyHologramDefinition> holograms = new ArrayList<>();

   public LobbyTemplateLayout() {
   }
}
