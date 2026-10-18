package com.drivedeck.player.spike;

/** Publication progress only; counters cannot prove device consumption or audible continuity. */
final class RecoveryProgress {
    static final long MAX_SAMPLE_MS=1250, MAX_BATCH_MS=2250;
    private long confirmedMs, pendingMs, longestMs;
    private int confirmedHolds;
    // 0 reset, 1 confirmed, 2 awaiting a subsequent audio publication.
    int sample(long wall, long clock, long audio, long pictures, long paint) {
        if(wall<500 || wall>MAX_SAMPLE_MS || clock<500 || clock>wall+500
                || audio<0 || pictures<=0 || paint<=0) return reset();
        if(audio==0) {
            pendingMs+=wall;
            if(pendingMs>MAX_SAMPLE_MS) return reset();
            return 2;
        }
        if(pendingMs+wall>MAX_BATCH_MS) return reset();
        if(pendingMs>0) confirmedHolds++;
        confirmedMs+=pendingMs+wall;
        pendingMs=0;
        longestMs=Math.max(longestMs,confirmedMs);
        return 1;
    }
    private int reset() { confirmedMs=0; pendingMs=0; return 0; }
    long terminalMs() { return pendingMs==0?confirmedMs:0; }
    long longestMs() { return longestMs; }
    long pendingMs() { return pendingMs; }
    int confirmedHolds() { return confirmedHolds; }
    boolean qualified() { return longestMs>=30000 && terminalMs()>=30000; }
}
