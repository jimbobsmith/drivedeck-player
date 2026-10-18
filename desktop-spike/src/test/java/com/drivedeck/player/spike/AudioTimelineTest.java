package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
public class AudioTimelineTest {
 @Test public void retainsOnlyKnownNumericAudioEvents() {
  assertEquals(new AudioTimeline.Event("early_silence",-125000),AudioTimeline.parse("main","playback way too early (-125000): playing silence"));
  assertEquals(new AudioTimeline.Event("late_drop",-4567),AudioTimeline.parse("main","buffer too late (-4567 us): dropped"));
  assertEquals(new AudioTimeline.Event("output_buffer_frames",14400),AudioTimeline.parse("wasapi","buffer size    : 14400 frames"));
  assertEquals(new AudioTimeline.Event("output_reset",0),AudioTimeline.parse("wasapi","reset"));
  assertEquals(new AudioTimeline.Event("resampling_stop",123),AudioTimeline.parse("main","resampling stopped (drift: 123 us)"));
 }
 @Test public void rejectsProviderPathsIdentifiersAndUnrelatedNativeMessages() {
  for(var value:java.util.List.of("https://media.example/?token=secret","opening file C:/Users/private/media.mp4","playback too late (4): up-sampling\nsecret","buffer too late (99999999999999999999999 us): dropped")) assertNull(AudioTimeline.parse("main",value));
  assertNull(AudioTimeline.parse("http","buffer too late (-4567 us): dropped"));
  assertNull(AudioTimeline.parse("main","reset"));
  assertNull(AudioTimeline.parse("main","nSamplesPerSec 44100"));
  assertNull(AudioTimeline.parse("wasapi","discarded audio buffer"));
 }
}
