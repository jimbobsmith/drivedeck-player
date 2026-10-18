package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
public class RecoveryDecisionsTest {
 @Test public void fullTraceIsFlushedAndOverflowFailsRatherThanDroppingTerminalEvidence() throws Exception {
  var root=Path.of(System.getenv().getOrDefault("DD_PLAYER_SCRATCH","D:/tmp/dd/player-desktop-spike"),"relay-tests");Files.createDirectories(root);
  var dir=Files.createTempDirectory(root,"decisions-");var file=dir.resolve(RecoveryDecisions.NAME);
  try {
   var trace=new RecoveryDecisions(dir);
   assertThrows(IllegalArgumentException.class,()->trace.append(1,2));
   for(int i=0;i<601;i++) trace.append(i,2,3,4,5,6,7,8);
   var lines=Files.readAllLines(file);assertEquals(602,lines.size());assertEquals("600,2,3,4,5,6,7,8",lines.getLast());
   assertThrows(java.io.IOException.class,()->trace.append(1,2,3,4,5,6,7,8));assertEquals(601,trace.samples());
   new RecoveryDecisions(dir);assertEquals(1,Files.readAllLines(file).size());
  } finally { Files.deleteIfExists(file);Files.deleteIfExists(dir); }
 }
}
