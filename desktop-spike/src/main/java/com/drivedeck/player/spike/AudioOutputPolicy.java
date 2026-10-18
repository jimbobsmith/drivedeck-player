package com.drivedeck.player.spike;
import java.util.*;
/** Explicit diagnostic selection; default output selection remains untouched. */
record AudioOutputPolicy(String requested) {
 static AudioOutputPolicy from(Map<String,String> env,boolean presentation) {
  String name=env.getOrDefault("DD_PLAYER_AUDIO_OUTPUT","default");
  if(!Set.of("default","mmdevice","directsound").contains(name)) throw new IllegalArgumentException("Unsupported audio output experiment");
  if(!presentation && !name.equals("default")) throw new IllegalArgumentException("Audio output experiment needs presentation");
  return new AudioOutputPolicy(name);
 }
 List<String> options() { return requested.equals("default") ? List.of() : List.of("--aout="+requested+",none"); }
}
