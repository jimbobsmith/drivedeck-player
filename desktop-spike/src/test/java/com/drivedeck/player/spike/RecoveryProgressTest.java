package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
public class RecoveryProgressTest {
    private RecoveryProgress stable() {
        var p=new RecoveryProgress();
        for(int i=0;i<30;i++) assertEquals(1,p.sample(1000,1000,43,60,60));
        assertTrue(p.qualified()); return p;
    }
    @Test public void publicationHoldNeedsSubsequentConfirmationIncludingAtCompletion() {
        var p=stable(); assertEquals(2,p.sample(1000,1000,0,60,60));
        assertFalse(p.qualified()); assertEquals(0,p.terminalMs());
        assertEquals(1,p.sample(1000,1000,86,60,60));
        assertTrue(p.qualified()); assertEquals(32000,p.terminalMs()); assertEquals(1,p.confirmedHolds());
    }
    @Test public void twoHeldDecisionsOrLatePublicationResetTerminalStability() {
        var p=stable();p.sample(1000,1000,0,60,60);
        assertEquals(0,p.sample(1000,1000,0,60,60)); assertFalse(p.qualified());
        p.sample(1000,1000,129,60,60);assertEquals(1000,p.terminalMs());assertFalse(p.qualified());
        p=stable();p.sample(1250,1000,0,60,60);
        assertEquals(0,p.sample(1001,1000,86,60,60));assertFalse(p.qualified());
    }
    @Test public void clockVideoPaintResetAndSchedulerGapsAreNeverWaived() {
        for(long[] row:new long[][]{{1000,0,86,60,60},{1000,1000,86,0,60},{1000,1000,86,60,0},
                {1000,1000,-1,60,60},{1251,1000,86,60,60},{499,1000,86,60,60},{1000,1501,86,60,60}}) {
            var p=stable();p.sample(1000,1000,0,60,60);
            assertEquals(0,p.sample(row[0],row[1],row[2],row[3],row[4]));assertFalse(p.qualified());
        }
    }
    @Test public void qualifiedDurationIsWallTimeRatherThanNumberOfSamples() {
        var p=new RecoveryProgress();for(int i=0;i<30;i++)p.sample(500,500,22,30,30);
        assertEquals(15000,p.terminalMs());assertFalse(p.qualified());
        for(int i=0;i<30;i++)p.sample(500,500,22,30,30);assertTrue(p.qualified());
    }
    @Test public void intermittentConfirmedBatchesCanAccumulateThirtySeconds() {
        var p=new RecoveryProgress();
        for(int i=0;i<30;i++){p.sample(1000,1000,i%2==0?0:86,60,60);}
        assertTrue(p.qualified());assertEquals(15,p.confirmedHolds());
    }
}
