package com.drivedeck.player.spike;
import java.nio.file.*;
public final class FakeWorker {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        System.err.println("DD_STAGE decode");
        switch (args[0]) {
            case "hang" -> Thread.sleep(60000);
            case "invalid" -> System.out.println("not-json");
            case "overflow" -> System.out.println("x".repeat(100000));
            case "unclean" -> System.out.println("{\"result\":\"passed\",\"stage\":\"complete\"}");
            case "provider" -> {
                System.out.println("{\"result\":\"failed\",\"errorType\":\"ProviderFailure\",\"stage\":\"extract\"}");
                System.exit(1);
            }
            default -> {
                System.out.println("{\"result\":\"passed\",\"stage\":\"complete\",\"cleanShutdown\":true}");
                if (args[0].equals("trailing")) System.out.println("unexpected provider output");
                if (args[0].equals("crashAfterSuccess")) Runtime.getRuntime().halt(37);
            }
        }
    }
}
