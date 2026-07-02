package net.elytrium.limboapi.api.protocol.item;

public interface ItemComponent<T> {
   String getName();

   ItemComponent<T> setValue(T var1);

   T getValue();
}
