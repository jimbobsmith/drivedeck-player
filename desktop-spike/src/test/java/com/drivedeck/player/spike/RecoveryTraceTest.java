package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
public class RecoveryTraceTest {
 @Test public void samplesAreFlushedBoundedAndOldRunDataIsReplaced() throws Exception {
  var root=Path.of(System.getenv().getOrDefault("DD_PLAYER_SCRATCH","D:/tmp/dd/player-desktop-spike"),"relay-tests");Files.createDirectories(root);
  var dir=Files.createTempDirectory(root,"trace-");var file=dir.resolve("recovery-diagnostics.csv");
  try {
   var trace=new RecoveryTrace(dir);var values=new long[27];values[0]=123;
   trace.append(values);assertEquals(2,Files.readAllLines(file).size());
   assertThrows(IllegalArgumentException.class,()->trace.append(1,2,3));
   for(int i=0;i<500;i++) trace.append(values);
   var rows=Files.readAllLines(file);assertEquals(301,rows.size());assertEquals(300,trace.samples());
   assertEquals(27,rows.get(0).split(",").length);assertEquals(27,rows.get(1).split(",").length);
   new RecoveryTrace(dir);assertEquals(1,Files.readAllLines(file).size());
  } finally { Files.deleteIfExists(file);Files.deleteIfExists(dir); }
 }
}
