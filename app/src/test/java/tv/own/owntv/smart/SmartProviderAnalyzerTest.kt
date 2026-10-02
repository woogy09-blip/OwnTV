package tv.own.owntv.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.smart.SmartProviderAnalyzer.ChannelCandidate
import tv.own.owntv.smart.SmartProviderAnalyzer.MatchType

/**
 * The Smart Provider Analyzer's two promises: it finds the same channel on another provider, and it
 * always answers in the same order.
 *
 * The ordering half is the half that rots silently. A comparator that quietly falls back on the
 * caller's list order looks perfect in a hand-written test and reshuffles a user's failover list on a
 * real device, so every ordering test here runs the *same* pool in several different input orders and
 * demands identical output. No randomness, no reliance on sorting being stable.
 *
 * No database, no player, no coroutines: rows are plain [ChannelEntity] values built exactly as the
 * app builds them elsewhere.
 */
class SmartProviderAnalyzerTest {

    private fun channel(
        id: Long = 0,
        sourceId: Long,
        remoteId: String? = null,
        name: String,
    ) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = name,
        streamUrl = "http://example.invalid/$sourceId/${remoteId ?: "-"}.ts",
        remoteId = remoteId,
    )

    /** "BBC One" normalizes to "bbc 1", so both the number-word and the HD-suffix variant reach it. */
    private val anchor = channel(id = 1, sourceId = 1, remoteId = "101", name = "BBC One")

    private fun pairs(anchor: ChannelEntity, pool: List<ChannelEntity>, limit: Int = Int.MAX_VALUE) =
        SmartProviderAnalyzer.analyze(anchor, pool, limit).map { it.sourceId to it.remoteId }

    // ---- 1. exact normalized name ------------------------------------------------------------

    @Test
    fun `an identical name on another provider is the strongest candidate`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")),
        )

        assertEquals(1, found.size)
        val top = found.single()
        assertEquals(1, top.rank)
        assertEquals(2L, top.sourceId)
        assertEquals("77", top.remoteId)
        assertEquals("BBC One", top.channelName)
        assertEquals(MatchType.EXACT_NAME, top.matchType)
        assertEquals(1.0, top.score, 0.0)
        // The provider identity, not the Room id — a later phase persists against this.
        assertEquals("2:LIVE:77", top.identityKey)
    }

    // ---- 2. case-only differences ------------------------------------------------------------

    @Test
    fun `a case-only difference still matches, one tier below an identical name`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(channel(sourceId = 2, remoteId = "77", name = "bbc one")),
        )

        assertEquals(MatchType.EXACT_NORMALIZED_NAME, found.single().matchType)
        assertEquals(1.0, found.single().score, 0.0)
        // ...and the provider's own spelling comes back, not a normalized one.
        assertEquals("bbc one", found.single().channelName)
    }

    // ---- 3. punctuation-only differences -----------------------------------------------------

    @Test
    fun `punctuation-only differences match`() {
        val discovery = channel(id = 2, sourceId = 1, remoteId = "99", name = "Discovery Channel")
        val pool = listOf(
            channel(sourceId = 2, remoteId = "9", name = "Discovery-Channel"),
            channel(sourceId = 3, remoteId = "9", name = "DISCOVERY.CHANNEL"),
        )

        val found = SmartProviderAnalyzer.analyze(discovery, pool)

        assertEquals(2, found.size)
        assertTrue(found.all { it.matchType == MatchType.EXACT_NORMALIZED_NAME })
        assertEquals(listOf(2L, 3L), found.map { it.sourceId })
    }

    // ---- 4. common channel-name variations ---------------------------------------------------

    @Test
    fun `quality tags, country prefixes and separators are not differences`() {
        // The variant pair EpgMatcher's own documentation uses as its worked example.
        val fuss = channel(id = 2, sourceId = 1, remoteId = "A9", name = "DE| FUSS-TV 3 ᴴᴰ")
        val pool = listOf(channel(sourceId = 2, remoteId = "9", name = "fusstv3.de"))

        val found = SmartProviderAnalyzer.analyze(fuss, pool)

        assertEquals(1, found.size)
        assertEquals(MatchType.STRONG_NAME, found.single().matchType)
        assertTrue(found.single().score >= EpgMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun `an HD suffix is cosmetic, and a spelled-out number equals its digit`() {
        val pool = listOf(
            channel(sourceId = 2, remoteId = "1", name = "BBC One HD"),
            channel(sourceId = 3, remoteId = "2", name = "bbc 1"),
        )

        val found = SmartProviderAnalyzer.analyze(anchor, pool)

        assertEquals(2, found.size)
        assertTrue(found.all { it.matchType == MatchType.EXACT_NORMALIZED_NAME })
    }

    // ---- 5. the same channel under different provider ids -----------------------------------

    @Test
    fun `the same channel is found even when the two providers use unrelated ids`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(channel(sourceId = 2, remoteId = "8842", name = "BBC One")),
        )

        // The ids have nothing in common; the name is the whole evidence.
        assertEquals("8842", found.single().remoteId)
        assertEquals("101", anchor.remoteId)
        assertEquals(MatchType.EXACT_NAME, found.single().matchType)
    }

    @Test
    fun `a shared provider id corroborates a matching name and is not evidence on its own`() {
        val pool = listOf(
            // Same id, unrelated name: a coincidence — every panel numbers its own channels, so
            // "101" is a different channel here. Must not match.
            channel(sourceId = 2, remoteId = "101", name = "Discovery Channel"),
            // Same id, agreeing name: the same channel, and the closest pairing the engine reports.
            channel(sourceId = 3, remoteId = "101", name = "BBC One HD"),
        )

        val found = SmartProviderAnalyzer.analyze(anchor, pool)

        assertEquals(1, found.size)
        assertEquals(3L, found.single().sourceId)
        assertEquals(MatchType.SAME_PROVIDER_ID, found.single().matchType)
        assertEquals("3:LIVE:101", found.single().identityKey)
    }

    // ---- 6. duplicate candidates ------------------------------------------------------------

    @Test
    fun `a provider channel listed twice yields one candidate`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(
                channel(sourceId = 2, remoteId = "77", name = "BBC One"),
                channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            ),
        )

        assertEquals(1, found.size)
        assertEquals(2L, found.single().sourceId)
    }

    @Test
    fun `a duplicate keeps the stronger of the two spellings`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(
                channel(sourceId = 2, remoteId = "77", name = "bbc one"),
                channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            ),
        )

        assertEquals(1, found.size)
        assertEquals(MatchType.EXACT_NAME, found.single().matchType)
        assertEquals("BBC One", found.single().channelName)
    }

    @Test
    fun `an id-less provider listing the channel twice under two spellings yields one candidate`() {
        // No provider id, so the fallback identity is the normalized name — and both rows reduce to
        // "bbc 1", so this is one provider channel seen twice, not two candidates.
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(
                channel(sourceId = 2, remoteId = null, name = "bbc-one"),
                channel(sourceId = 2, remoteId = null, name = "BBC One"),
            ),
        )

        assertEquals(1, found.size)
        assertEquals(2L, found.single().sourceId)
        assertNull(found.single().remoteId)
        // The stronger spelling survives, exactly as it does on the id-bearing path.
        assertEquals(MatchType.EXACT_NAME, found.single().matchType)
        assertEquals("BBC One", found.single().channelName)
    }

    // ---- 7. unrelated channels --------------------------------------------------------------

    @Test
    fun `unrelated channels are not candidates`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(
                channel(sourceId = 2, remoteId = "1", name = "Discovery Channel"),
                channel(sourceId = 3, remoteId = "2", name = "Sky Sports 4"),
                channel(sourceId = 4, remoteId = "3", name = "   "),
            ),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a different embedded channel number is never the same channel`() {
        val skySportsThree = channel(id = 3, sourceId = 1, remoteId = "S3", name = "Sky Sports 3")
        val pool = listOf(
            channel(sourceId = 2, remoteId = "3", name = "Sky Sports 2"),
            channel(sourceId = 3, remoteId = "3", name = "Sky Sports 3"),
        )

        val found = SmartProviderAnalyzer.analyze(skySportsThree, pool)

        // "Sky Sports 2" scores far too close on the letters alone, and EpgMatcher's channel-number
        // guard is what stops it — reused here rather than re-derived.
        assertEquals(1, found.size)
        assertEquals("Sky Sports 3", found.single().channelName)
        assertEquals(MatchType.EXACT_NAME, found.single().matchType)
    }

    @Test
    fun `a number on only one side can reach review but can never auto-apply`() {
        // EpgMatcher caps a pair where one side carries a number the other lacks at DIGIT_MISSING_CAP,
        // and that cap sits deliberately below AUTO_THRESHOLD: "Sky Sports" -> "Sky Sports 3" is worth
        // showing a human, but is not the same channel unattended. This pins the ceiling for the
        // analyzer, which is what a later phase keys its decisions on.
        val sky = channel(id = 5, sourceId = 1, remoteId = "S", name = "Sky Sports")
        val pool = listOf(channel(sourceId = 2, remoteId = "3", name = "Sky Sports 3"))

        val found = SmartProviderAnalyzer.analyze(sky, pool)

        assertEquals(1, found.size)
        // REVIEW_NAME, not STRONG_NAME: the tier itself proves it never reached auto-apply.
        assertEquals(MatchType.REVIEW_NAME, found.single().matchType)
        assertTrue(found.single().score < EpgMatcher.AUTO_THRESHOLD)
        assertTrue(found.single().score >= EpgMatcher.REVIEW_THRESHOLD)
    }

    // ---- 8. several providers carrying the same channel -------------------------------------

    @Test
    fun `every provider carrying the channel comes back, provider order first`() {
        val pool = listOf(
            channel(sourceId = 5, remoteId = "5", name = "BBC One"),
            channel(sourceId = 2, remoteId = "2", name = "BBC One"),
            channel(sourceId = 3, remoteId = "3", name = "BBC One"),
        )

        val found = SmartProviderAnalyzer.analyze(anchor, pool)

        assertEquals(listOf(2L, 3L, 5L), found.map { it.sourceId })
        assertEquals(listOf(1, 2, 3), found.map { it.rank })
        assertTrue(found.all { it.matchType == MatchType.EXACT_NAME })
    }

    // ---- 9. null and blank remote ids -------------------------------------------------------

    @Test
    fun `a candidate whose provider gives no id is still matched by name alone`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(channel(sourceId = 2, remoteId = null, name = "BBC One")),
        )

        assertEquals(1, found.size)
        assertEquals(2L, found.single().sourceId)
        assertNull(found.single().remoteId)
        // Nothing provider-stable to key on, and enginePinKey says so rather than inventing one.
        assertNull(found.single().identityKey)
    }

    @Test
    fun `an anchor whose provider gives no id can still be matched`() {
        val noId = channel(id = 7, sourceId = 1, remoteId = null, name = "BBC One")

        val found = SmartProviderAnalyzer.analyze(
            noId,
            listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")),
        )

        assertEquals(1, found.size)
        assertEquals("77", found.single().remoteId)
        assertEquals("2:LIVE:77", found.single().identityKey)
    }

    @Test
    fun `two id-less candidates from different providers are two candidates, not one`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(
                channel(sourceId = 2, remoteId = null, name = "BBC One"),
                channel(sourceId = 3, remoteId = null, name = "BBC One"),
            ),
        )

        // A null identityKey marks a row as runtime-only, not as a value two rows may share: these are
        // two different providers and must not be collapsed the way a repeated provider id would be.
        assertEquals(listOf(2L, 3L), found.map { it.sourceId })
        assertTrue(found.all { it.identityKey == null })
    }

    @Test
    fun `a blank remote id is read as no id and never equals a real one`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor, // carries "101"
            listOf(channel(sourceId = 2, remoteId = "   ", name = "BBC One")),
        )

        assertEquals(1, found.size)
        assertNull(found.single().remoteId)
        // "   " must not be read as the anchor's "101", so this is a name match, not an id match.
        assertEquals(MatchType.EXACT_NAME, found.single().matchType)
    }

    // ---- 10. determinism --------------------------------------------------------------------

    @Test
    fun `the answer never depends on the order the candidates arrive in`() {
        val pool = listOf(
            channel(sourceId = 4, remoteId = "4", name = "Discovery Channel"),
            channel(sourceId = 3, remoteId = null, name = "bbc one"),
            channel(sourceId = 2, remoteId = "2", name = "BBC One"),
            channel(sourceId = 5, remoteId = "5", name = "BBC One HD"),
            channel(sourceId = 6, remoteId = "6", name = "Discovery Science"),
        )

        val forward = pairs(anchor, pool)
        val backward = pairs(anchor, pool.reversed())
        val permuted = pairs(anchor, listOf(pool[3], pool[0], pool[4], pool[2], pool[1]))

        assertTrue(forward.isNotEmpty())
        assertEquals(forward, backward)
        assertEquals(forward, permuted)
        // ...and the exact match outranks the normalized-only ones, whatever the input order.
        assertEquals(listOf(2L, 3L, 5L), forward.map { it.first })
    }

    @Test
    fun `repeated calls on the same input are identical, so nothing is remembered between them`() {
        val pool = listOf(
            channel(sourceId = 3, remoteId = null, name = "bbc one"),
            channel(sourceId = 2, remoteId = "2", name = "BBC One"),
            channel(sourceId = 4, remoteId = "4", name = "BBC One HD"),
        )

        assertEquals(SmartProviderAnalyzer.analyze(anchor, pool), SmartProviderAnalyzer.analyze(anchor, pool))
    }

    @Test
    fun `equal matches are separated by provider id, then by name — never by chance`() {
        // Same tier (exact-normalized), same score, same source: only the provider id can separate
        // them, and "aa" must come before "zz" in either input order.
        val pool = listOf(
            channel(sourceId = 2, remoteId = "zz", name = "BBC-ONE"),
            channel(sourceId = 2, remoteId = "aa", name = "bbc one"),
        )

        val forward = pairs(anchor, pool)
        assertEquals(listOf(2L to "aa", 2L to "zz"), forward)
        assertEquals(forward, pairs(anchor, pool.reversed()))
    }

    @Test
    fun `a candidate whose provider gives no id ranks behind one that does`() {
        // Identical tier, identical score, identical source — only "has an id" separates them.
        val pool = listOf(
            channel(sourceId = 2, remoteId = null, name = "BBC-ONE"),
            channel(sourceId = 2, remoteId = "77", name = "bbc one"),
        )

        val forward = pairs(anchor, pool)
        assertEquals(listOf(2L to "77", 2L to null), forward)
        assertEquals(forward, pairs(anchor, pool.reversed()))
    }

    @Test
    fun `the last tie-break is the channel name, and it never depends on input order`() {
        val sky = channel(id = 9, sourceId = 1, remoteId = "S", name = "Sky Sports")
        // Both rows tie on every earlier ordering key: the same tier, the same capped score, the same
        // source, and neither carries a provider id. Only the raw name is left to order them.
        val pool = listOf(
            channel(sourceId = 2, remoteId = null, name = "Sky Sports 4"),
            channel(sourceId = 2, remoteId = null, name = "Sky Sports 3"),
        )

        val forward = SmartProviderAnalyzer.analyze(sky, pool)
        val backward = SmartProviderAnalyzer.analyze(sky, pool.reversed())

        assertTrue(forward.all { it.matchType == MatchType.REVIEW_NAME })
        assertEquals(forward[0].score, forward[1].score, 0.0)
        assertEquals(forward[0].sourceId, forward[1].sourceId)
        assertEquals(listOf("Sky Sports 3", "Sky Sports 4"), forward.map { it.channelName })
        assertEquals(forward, backward)
    }

    @Test
    fun `a sibling channel name is offered, ranked below and honestly scored`() {
        val discovery = channel(id = 2, sourceId = 1, remoteId = "99", name = "Discovery Channel")
        val pool = listOf(
            channel(sourceId = 2, remoteId = "2", name = "Discovery Science"),
            channel(sourceId = 3, remoteId = "2", name = "DISCOVERY-CHANNEL"),
        )

        val found = SmartProviderAnalyzer.analyze(discovery, pool)

        assertEquals(listOf(3L, 2L), found.map { it.sourceId })
        assertEquals(MatchType.EXACT_NORMALIZED_NAME, found[0].matchType)
        assertEquals(1.0, found[0].score, 0.0)
        // Related, not the same channel: below auto-apply, but above the engine's floor.
        assertTrue(found[1].matchType.rank > MatchType.EXACT_NORMALIZED_NAME.rank)
        assertTrue(found[1].score < EpgMatcher.AUTO_THRESHOLD)
        assertTrue(found[1].score >= EpgMatcher.REVIEW_THRESHOLD)
    }

    // ---- 11. the anchor itself --------------------------------------------------------------

    @Test
    fun `the anchor is never offered as its own failover target`() {
        val pool = listOf(
            anchor,
            // The same provider id on the anchor's own source: the same channel, not a candidate.
            channel(id = 2, sourceId = 1, remoteId = "101", name = "BBC One"),
            channel(sourceId = 2, remoteId = "77", name = "BBC One"),
        )

        val found = SmartProviderAnalyzer.analyze(anchor, pool)

        assertEquals(1, found.size)
        assertEquals(2L, found.single().sourceId)
    }

    @Test
    fun `an id-less anchor excludes the matching row on its own source but not other providers`() {
        val noId = channel(id = 7, sourceId = 1, remoteId = null, name = "BBC One")
        val pool = listOf(
            channel(id = 8, sourceId = 1, remoteId = null, name = "bbc-one"),
            channel(sourceId = 2, remoteId = null, name = "BBC One"),
        )

        assertEquals(listOf(2L), SmartProviderAnalyzer.analyze(noId, pool).map { it.sourceId })
    }

    @Test
    fun `a perfect name match on the anchor's own source is never a candidate`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor, // source 1, provider id "101"
            listOf(
                // The same channel under a second id, but still the anchor's own provider: the same
                // channel again, not somewhere else to fail over to.
                channel(sourceId = 1, remoteId = "999", name = "BBC One"),
                channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            ),
        )

        assertEquals(listOf(2L), found.map { it.sourceId })
    }

    @Test
    fun `a same-source row is excluded however its name is spelled`() {
        val found = SmartProviderAnalyzer.analyze(
            anchor,
            listOf(
                // Every one of these normalizes to "bbc 1", so name matching alone would accept them.
                channel(sourceId = 1, remoteId = "999", name = "bbc-one"),
                channel(sourceId = 1, remoteId = "1000", name = "BBC One HD"),
                channel(sourceId = 2, remoteId = "77", name = "bbc 1"),
            ),
        )

        assertEquals(listOf(2L), found.map { it.sourceId })
    }

    // ---- 12. limits and degenerate input ----------------------------------------------------

    @Test
    fun `a limit keeps the strongest candidates in order`() {
        val pool = (2L..6L).map { channel(sourceId = it, remoteId = "$it", name = "BBC One") }

        val found = SmartProviderAnalyzer.analyze(anchor, pool, limit = 2)

        assertEquals(listOf(2L, 3L), found.map { it.sourceId })
        assertEquals(listOf(1, 2), found.map { it.rank })
    }

    @Test
    fun `duplicate rows are removed before the limit, so two providers still fill two slots`() {
        val pool = listOf(
            // The strongest entries are the *same* provider channel listed twice. A limit applied
            // before de-duplication would spend both slots on it and hand back a single provider.
            channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            channel(sourceId = 3, remoteId = "88", name = "BBC One"),
        )

        val found = SmartProviderAnalyzer.analyze(anchor, pool, limit = 2)

        assertEquals(listOf(2L, 3L), found.map { it.sourceId })
        assertEquals(listOf(1, 2), found.map { it.rank })
    }

    @Test
    fun `an anchor name that is nothing but cosmetic noise matches nothing`() {
        val pool = listOf(
            channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            channel(sourceId = 3, remoteId = "88", name = "Discovery Channel"),
        )

        // Each of these is entirely NOISE to the normalizer, so no name is left to match on. The
        // answer is an empty list rather than a list of everything, and a same-name row in the pool
        // would still only match by exact name equality.
        for (junk in listOf("4K", "HD", "1080p", "UHD")) {
            val junky = channel(id = 1, sourceId = 1, remoteId = "j", name = junk)
            assertTrue(
                "a channel named only \"$junk\" has no name left to match on",
                SmartProviderAnalyzer.analyze(junky, pool).isEmpty(),
            )
        }
    }

    @Test
    fun `a blank anchor name, an empty pool and a non-positive limit all yield nothing`() {
        val blank = channel(id = 4, sourceId = 1, remoteId = "1", name = "   ")
        val other = channel(sourceId = 2, remoteId = "2", name = "BBC One")

        assertTrue(SmartProviderAnalyzer.analyze(blank, listOf(other)).isEmpty())
        assertTrue(SmartProviderAnalyzer.analyze(anchor, emptyList()).isEmpty())
        assertTrue(SmartProviderAnalyzer.analyze(anchor, listOf(other), limit = 0).isEmpty())
    }

    // ---- 13. what a candidate is not --------------------------------------------------------

    @Test
    fun `a candidate describes a channel without carrying its stream or logo`() {
        val fields = ChannelCandidate::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue(
            "no member may name a url, stream or logo: $fields",
            fields.none { "url" in it || "stream" in it || "logo" in it },
        )
    }
}
