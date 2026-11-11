package org.schabi.newpipe.mediasync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncHydratorTest {
    @Test fun recognisesOnlyExactPlaceholderTitles() {
        assertTrue(MediaSyncHydrator.isPlaceholder("Synced video: aBcDeFgH123", "aBcDeFgH123"))
        assertTrue(MediaSyncHydrator.isPlaceholder("Synced resume: aBcDeFgH123", "aBcDeFgH123"))
        assertFalse(MediaSyncHydrator.isPlaceholder("Synced video: aBcDeFgH123 (my note)", "aBcDeFgH123"))
        assertFalse(MediaSyncHydrator.isPlaceholder("Synced video: zzzzzzzzzzz", "aBcDeFgH123"))
        assertFalse(MediaSyncHydrator.isPlaceholder("A real title", "aBcDeFgH123"))
    }

    @Test fun parsesTitleAndAuthorAndCleansControlCharacters() {
        assertEquals("Real  title" to "The Channel", MediaSyncHydrator.parse("""{"title":"Real\u0007 title","author_name":"The Channel"}"""))
        assertEquals("T" to "", MediaSyncHydrator.parse("""{"title":"T"}"""))
    }

    @Test fun rejectsEmptyOversizedAndInvalidAnswers() {
        assertNull(MediaSyncHydrator.parse("""{"title":"   "}"""))
        assertNull(MediaSyncHydrator.parse("not json"))
        assertNull(MediaSyncHydrator.parse("""{"title":"${"x".repeat(20000)}"}"""))
    }
}
