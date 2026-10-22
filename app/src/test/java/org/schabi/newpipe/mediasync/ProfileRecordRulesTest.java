package org.schabi.newpipe.mediasync;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileRecordRulesTest {
    private static final String PROFILE = "11111111-1111-4111-8111-111111111111";
    private static final String CHANNEL = "UCabcdefghijklmnopqrstuv";

    @Test public void nameLimitCountsUtf8BytesRatherThanCharacters() {
        assertTrue(ProfileRecordRules.validName("é".repeat(80)));
        assertFalse(ProfileRecordRules.validName("é".repeat(81)));
        assertTrue(ProfileRecordRules.validName("a".repeat(160)));
        assertFalse(ProfileRecordRules.validName("a".repeat(161)));
    }
    @Test public void nameRejectsBlankAndControlCharacters() {
        for (String value : new String[]{"", "  ", "\t", "Trip\nname", "Trip\u0000name", "Trip\u007fname"}) {
            assertFalse(value, ProfileRecordRules.validName(value));
        }
        assertFalse(ProfileRecordRules.validName(null));
        assertTrue(ProfileRecordRules.validName("Family 🚗"));
        assertFalse(ProfileRecordRules.validName("\uFEFF"));
        assertTrue(ProfileRecordRules.validName("\uFEFFTrip"));
        assertFalse(ProfileRecordRules.validName("Bad\uD800"));
        assertFalse(ProfileRecordRules.validName("Bad\uDC00"));
    }
    @Test public void colorsAreExactRgbValuesWithoutCssOrAlpha() {
        assertTrue(ProfileRecordRules.validColor("#12aBcD"));
        for (String color : new String[]{"red", "#fff", "#12345678", "#12345G", "#123456 ", " #123456"}) {
            assertFalse(color, ProfileRecordRules.validColor(color));
        }
        assertFalse(ProfileRecordRules.validColor(null));
    }
    @Test public void membershipIdentityBindsExactProfileAndCanonicalChannel() {
        assertTrue(ProfileRecordRules.validMembership(PROFILE + ":" + CHANNEL, PROFILE, CHANNEL));
        assertFalse(ProfileRecordRules.validMembership(PROFILE + ":" + CHANNEL, PROFILE, "UC" + "z".repeat(22)));
        assertFalse(ProfileRecordRules.validMembership(PROFILE + ":" + CHANNEL, PROFILE.replace("4111", "3111"), CHANNEL));
        assertFalse(ProfileRecordRules.validMembership(PROFILE + ":" + CHANNEL, PROFILE, "https://youtube.com/" + CHANNEL));
        assertFalse(ProfileRecordRules.validMembership(PROFILE + ":" + CHANNEL, PROFILE, CHANNEL.substring(1)));
    }
}
