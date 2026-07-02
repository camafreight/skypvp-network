package net.elytrium.limboapi.api.protocol.packets.data;

import com.velocitypowered.api.network.ProtocolVersion;
import java.awt.image.BufferedImage;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class MapPalette {
   private static final Map<MapPalette.MapVersion, byte[]> REMAP_BUFFERS = new EnumMap<>(MapPalette.MapVersion.class);
   private static final byte[] MAIN_BUFFER = readBuffer("/mapping/colors_main_map");
   @Deprecated
   public static final byte WHITE = 34;
   public static final byte TRANSPARENT = 0;

   private static byte[] readBuffer(String filename) {
      try {
         byte[] var2;
         try (InputStream stream = MapPalette.class.getResourceAsStream(filename)) {
            var2 = Objects.requireNonNull(stream).readAllBytes();
         }

         return var2;
      } catch (IOException var6) {
         throw new IOError(var6);
      }
   }

   public static int[] imageToBytes(BufferedImage image) {
      return imageToBytes(image, ProtocolVersion.MINIMUM_VERSION);
   }

   public static int[] imageToBytes(BufferedImage image, ProtocolVersion version) {
      int[] result = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
      return imageToBytes(result, result, version);
   }

   public static int[] imageToBytes(int[] image, ProtocolVersion version) {
      return imageToBytes(image, new int[image.length], version);
   }

   public static int[] imageToBytes(int[] from, int[] to, ProtocolVersion version) {
      for (int i = 0; i < from.length; i++) {
         to[i] = tryFastMatchColor(from[i], version);
      }

      return to;
   }

   public static byte[] imageToBytes(int[] from, byte[] to, ProtocolVersion version) {
      for (int i = 0; i < from.length; i++) {
         to[i] = tryFastMatchColor(from[i], version);
      }

      return to;
   }

   public static byte tryFastMatchColor(int rgb, ProtocolVersion version) {
      if (getAlpha(rgb) < 128) {
         return 0;
      } else {
         MapPalette.MapVersion mapVersion = MapPalette.MapVersion.fromProtocolVersion(version);
         byte originalColorID = MAIN_BUFFER[rgb & 16777215];
         return mapVersion == MapPalette.MapVersion.MAXIMUM_VERSION ? originalColorID : remapByte(REMAP_BUFFERS.get(mapVersion), originalColorID);
      }
   }

   private static int getAlpha(int rgb) {
      return (rgb & 0xFF000000) >>> 24;
   }

   public static int[] convertImage(int[] image, MapPalette.MapVersion version) {
      return convertImage(image, new int[image.length], version);
   }

   public static int[] convertImage(int[] from, int[] to, MapPalette.MapVersion version) {
      byte[] remapBuffer = REMAP_BUFFERS.get(version);

      for (int i = 0; i < from.length; i++) {
         to[i] = remapByte(remapBuffer, (byte)from[i]);
      }

      return to;
   }

   public static byte[] convertImage(byte[] from, byte[] to, MapPalette.MapVersion version) {
      byte[] remapBuffer = REMAP_BUFFERS.get(version);

      for (int i = 0; i < from.length; i++) {
         to[i] = remapByte(remapBuffer, from[i]);
      }

      return to;
   }

   public static byte[] convertImage(int[] from, byte[] to, MapPalette.MapVersion version) {
      byte[] remapBuffer = REMAP_BUFFERS.get(version);

      for (int i = 0; i < from.length; i++) {
         to[i] = remapByte(remapBuffer, (byte)from[i]);
      }

      return to;
   }

   private static byte remapByte(byte[] remapBuffer, byte oldByte) {
      return remapBuffer[Byte.toUnsignedInt(oldByte)];
   }

   static {
      for (MapPalette.MapVersion version : MapPalette.MapVersion.values()) {
         REMAP_BUFFERS.put(version, readBuffer("/mapping/colors_" + version.toString().toLowerCase(Locale.ROOT) + "_map"));
      }
   }

   public static enum MapVersion {
      MINIMUM_VERSION(EnumSet.range(ProtocolVersion.MINECRAFT_1_7_2, ProtocolVersion.MINECRAFT_1_7_6)),
      MINECRAFT_1_8(EnumSet.range(ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.MINECRAFT_1_11_1)),
      MINECRAFT_1_12(EnumSet.range(ProtocolVersion.MINECRAFT_1_12, ProtocolVersion.MINECRAFT_1_15_2)),
      MINECRAFT_1_16(EnumSet.range(ProtocolVersion.MINECRAFT_1_16, ProtocolVersion.MINECRAFT_1_16_4)),
      MINECRAFT_1_17(EnumSet.range(ProtocolVersion.MINECRAFT_1_17, ProtocolVersion.MAXIMUM_VERSION));

      private static final EnumMap<ProtocolVersion, MapPalette.MapVersion> VERSIONS_MAP = new EnumMap<>(ProtocolVersion.class);
      public static final MapPalette.MapVersion MAXIMUM_VERSION = MINECRAFT_1_17;
      private final EnumSet<ProtocolVersion> versions;

      private MapVersion(EnumSet<ProtocolVersion> versions) {
         this.versions = versions;
      }

      public EnumSet<ProtocolVersion> getVersions() {
         return this.versions;
      }

      public static MapPalette.MapVersion fromProtocolVersion(ProtocolVersion version) {
         return VERSIONS_MAP.get(version);
      }

      static {
         for (MapPalette.MapVersion value : values()) {
            value.versions.forEach(version -> VERSIONS_MAP.put(version, value));
         }
      }
   }
}
