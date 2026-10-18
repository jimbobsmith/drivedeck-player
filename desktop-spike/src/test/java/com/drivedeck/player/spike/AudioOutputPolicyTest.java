package com.drivedeck.player.spike;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class AudioOutputPolicyTest {
 @Test public void defaultDoesNotOverrideNativeOutput() {
  assertTrue(AudioOutputPolicy.from(Map.of(),true).options().isEmpty());
  assertTrue(AudioOutputPolicy.from(Map.of(),false).options().isEmpty());
 }
 @Test public void diagnosticOutputDoesNotSilentlyFallBackToAnotherBackend() {
  assertEquals(List.of("--aout=directsound,none"),AudioOutputPolicy.from(Map.of("DD_PLAYER_AUDIO_OUTPUT","directsound"),true).options());
  assertEquals(List.of("--aout=mmdevice,none"),AudioOutputPolicy.from(Map.of("DD_PLAYER_AUDIO_OUTPUT","mmdevice"),true).options());
 }
 @Test public void rejectsArbitraryModulesOrHeadlessOverride() {
  assertThrows(IllegalArgumentException.class,()->AudioOutputPolicy.from(Map.of("DD_PLAYER_AUDIO_OUTPUT","http://private"),true));
  assertThrows(IllegalArgumentException.class,()->AudioOutputPolicy.from(Map.of("DD_PLAYER_AUDIO_OUTPUT","directsound"),false));
 }
}
