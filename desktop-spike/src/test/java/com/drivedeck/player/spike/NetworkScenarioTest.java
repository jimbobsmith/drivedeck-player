package com.drivedeck.player.spike;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
public class NetworkScenarioTest {
 @Test public void faultsRequireBoundedLongObservation() {
   assertFalse(new NetworkScenario(Map.of(),20).enabled());
   var control=new NetworkScenario(Map.of("DD_PLAYER_NETWORK_FAULT","relay"),20);
   assertFalse(control.enabled()); assertTrue(control.usesRelay());
   assertTrue(new NetworkScenario(Map.of("DD_PLAYER_NETWORK_FAULT","disconnect"),120).enabled());
   for(var env:java.util.List.of(Map.of("DD_PLAYER_NETWORK_FAULT","unknown"),Map.of("DD_PLAYER_NETWORK_DOWN_SECONDS","46")))
     assertThrows(IllegalArgumentException.class,()->new NetworkScenario(env,120));
   assertThrows(IllegalArgumentException.class,()->new NetworkScenario(Map.of("DD_PLAYER_NETWORK_FAULT","hold"),20));
 }
 @Test public void restartingAtZeroOrJumpingAheadCannotProveRecovery() {
   assertFalse(NetworkScenario.recoveryPosition(40000,1000,1000));
   assertFalse(NetworkScenario.recoveryPosition(40000,60000,1000));
   assertTrue(NetworkScenario.recoveryPosition(40000,42000,1000));
 }
 @Test public void instantOfCutCannotProveAStall() {
   assertFalse(NetworkScenario.outputStalled(0,0,0));
   assertFalse(NetworkScenario.outputStalled(1000,1000,50));
   assertTrue(NetworkScenario.outputStalled(1000,0,0));
 }
 @Test public void noFaultOrNativePlayingAloneCannotProveRecovery() {
   var scenario=new NetworkScenario(Map.of("DD_PLAYER_NETWORK_FAULT","hold"),120);
   assertThrows(java.io.IOException.class,scenario::requireSuccess);
   assertEquals("recovery_not_proven",scenario.report().getString("terminalReason"));
 }
 @Test public void everyRecoveredOutputMustAdvanceWithoutWaivingAClockOrCounterStall() {
   assertTrue(NetworkScenario.progressing(1004,43,61,60));
   assertFalse(NetworkScenario.progressing(0,0,11,13)); // Observed diagnostic interruption.
   assertFalse(NetworkScenario.progressing(499,43,61,60));
   assertFalse(NetworkScenario.progressing(1000,0,61,60));
   assertFalse(NetworkScenario.progressing(1000,43,0,60));
   assertFalse(NetworkScenario.progressing(1000,43,61,0));
   assertFalse(NetworkScenario.progressing(1000,-43,61,60)); // Counter reset is not progress.
 }
 @Test public void deadlineTakesPrecedenceOverReadyOutputAndAnUnusedRestart() {
   assertEquals(NetworkScenario.RecoveryAction.DEADLINE,NetworkScenario.recoveryAction(30304,true,true)); // Observed late historical pass.
   assertEquals(NetworkScenario.RecoveryAction.DEADLINE,NetworkScenario.recoveryAction(30000,true,false));
   assertEquals(NetworkScenario.RecoveryAction.DEADLINE,NetworkScenario.recoveryAction(30001,false,false));
   assertEquals(NetworkScenario.RecoveryAction.ACCEPT,NetworkScenario.recoveryAction(29999,true,true));
   assertEquals(NetworkScenario.RecoveryAction.WAIT,NetworkScenario.recoveryAction(9999,false,false));
   assertEquals(NetworkScenario.RecoveryAction.RESTART,NetworkScenario.recoveryAction(10000,false,false));
   assertEquals(NetworkScenario.RecoveryAction.WAIT,NetworkScenario.recoveryAction(10000,false,true));
 }
}
