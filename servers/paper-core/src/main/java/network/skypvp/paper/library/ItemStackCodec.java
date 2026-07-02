package network.skypvp.paper.library;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class ItemStackCodec {
   private ItemStackCodec() {
   }

   public static String encode(ItemStack item) {
      try {
         ByteArrayOutputStream output = new ByteArrayOutputStream();
         BukkitObjectOutputStream stream = new BukkitObjectOutputStream(output);

         try {
            stream.writeObject(item);
         } catch (Throwable var6) {
            try {
               stream.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }

            throw var6;
         }

         stream.close();
         return Base64.getEncoder().encodeToString(output.toByteArray());
      } catch (IOException var7) {
         throw new IllegalStateException("Failed to encode item stack", var7);
      }
   }

   public static ItemStack decode(String payload) {
      try {
         byte[] data = Base64.getDecoder().decode(payload);
         BukkitObjectInputStream stream = new BukkitObjectInputStream(new ByteArrayInputStream(data));

         ItemStack var5;
         try {
            if (!(stream.readObject() instanceof ItemStack item)) {
               throw new IllegalStateException("Decoded payload is not an ItemStack");
            }

            var5 = item;
         } catch (Throwable var7) {
            try {
               stream.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }

            throw var7;
         }

         stream.close();
         return var5;
      } catch (ClassNotFoundException | IOException var8) {
         throw new IllegalStateException("Failed to decode item stack", var8);
      }
   }
}
