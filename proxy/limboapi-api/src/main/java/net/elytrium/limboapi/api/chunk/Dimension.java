package net.elytrium.limboapi.api.chunk;

public enum Dimension {
   OVERWORLD("minecraft:overworld", 0, 0, 28, true, BuiltInBiome.PLAINS),
   NETHER("minecraft:the_nether", -1, 1, 16, false, BuiltInBiome.NETHER_WASTES),
   THE_END("minecraft:the_end", 1, 2, 16, false, BuiltInBiome.THE_END);

   private final String key;
   private final int legacyID;
   private final int modernID;
   private final int maxSections;
   private final boolean hasLegacySkyLight;
   private final BuiltInBiome defaultBiome;

   private Dimension(String key, int legacyID, int modernID, int maxSections, boolean hasLegacySkyLight, BuiltInBiome defaultBiome) {
      this.key = key;
      this.legacyID = legacyID;
      this.modernID = modernID;
      this.maxSections = maxSections;
      this.hasLegacySkyLight = hasLegacySkyLight;
      this.defaultBiome = defaultBiome;
   }

   public String getKey() {
      return this.key;
   }

   public int getLegacyID() {
      return this.legacyID;
   }

   public int getModernID() {
      return this.modernID;
   }

   public int getMaxSections() {
      return this.maxSections;
   }

   public boolean hasLegacySkyLight() {
      return this.hasLegacySkyLight;
   }

   public BuiltInBiome getDefaultBiome() {
      return this.defaultBiome;
   }
}
