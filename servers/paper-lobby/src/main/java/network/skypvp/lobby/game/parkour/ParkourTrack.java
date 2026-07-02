package network.skypvp.lobby.game.parkour;

import java.util.ArrayList;
import java.util.List;

public class ParkourTrack {
   private String name;
   private ParkourLocation start;
   private List<ParkourLocation> checkpoints = new ArrayList<>();
   private ParkourLocation finish;

   public ParkourTrack() {
   }

   public ParkourTrack(String name) {
      this.name = name;
   }

   public String getName() {
      return this.name;
   }

   public void setName(String name) {
      this.name = name;
   }

   public ParkourLocation getStart() {
      return this.start;
   }

   public void setStart(ParkourLocation start) {
      this.start = start;
   }

   public List<ParkourLocation> getCheckpoints() {
      return this.checkpoints;
   }

   public void setCheckpoints(List<ParkourLocation> checkpoints) {
      this.checkpoints = checkpoints;
   }

   public ParkourLocation getFinish() {
      return this.finish;
   }

   public void setFinish(ParkourLocation finish) {
      this.finish = finish;
   }

   public void addCheckpoint(ParkourLocation loc) {
      this.checkpoints.add(loc);
   }
}
