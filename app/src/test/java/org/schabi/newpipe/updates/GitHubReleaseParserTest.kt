// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseParserTest {
    @Test
    fun `parser keeps bounded release and private asset API metadata`() {
        val parsed = GitHubReleaseParser.parseReleaseList(
            """
            [{
              "id": 42,
              "tag_name": "v0.1.0-alpha.258",
              "name": "DriveDeck alpha 258",
              "body": "Updater release",
              "published_at": "2026-08-10T10:00:00Z",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name":"release-manifest.json","url":"https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/1","size":1200},
                {"name":"app-arm64-v8a-release.apk","url":"https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/2","size":200000000}
              ]
            }]
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals(42L, parsed.single().id)
        assertTrue(parsed.single().prerelease)
        assertFalse(parsed.single().draft)
        assertEquals(2, parsed.single().assets.size)
    }

    @Test(expected = GitHubReleaseException::class)
    fun `parser rejects non-array release payload`() {
        GitHubReleaseParser.parseReleaseList("{\"message\":\"not found\"}")
    }

    @Test
    fun `parser drops untrusted asset URLs`() {
        val parsed = GitHubReleaseParser.parseReleaseList(
            """[{"id":1,"tag_name":"v1","assets":[{"name":"x.apk","url":"https://evil.example/x","size":2000000}]}]"""
        )

        assertTrue(parsed.single().assets.isEmpty())
    }
}
