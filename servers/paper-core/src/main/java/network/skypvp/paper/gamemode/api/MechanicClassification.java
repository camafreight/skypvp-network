package network.skypvp.paper.gamemode.api;

import java.util.Objects;

/**
 * Identifies and classifies a single game mechanic: a unique {@code id}, its
 * {@link GameMechanicScope}, the owning module ({@code owner}), and a human
 * {@code description}. All components are validated and trimmed on construction.
 */
public record MechanicClassification(String id, GameMechanicScope scope, String owner, String description) {
   public MechanicClassification {
      id = Objects.requireNonNull(id, "id").trim();
      scope = Objects.requireNonNull(scope, "scope");
      owner = Objects.requireNonNull(owner, "owner").trim();
      description = Objects.requireNonNull(description, "description").trim();
      if (id.isEmpty()) {
         throw new IllegalArgumentException("id must not be empty");
      }
      if (owner.isEmpty()) {
         throw new IllegalArgumentException("owner must not be empty");
      }
      if (description.isEmpty()) {
         throw new IllegalArgumentException("description must not be empty");
      }
   }
}
