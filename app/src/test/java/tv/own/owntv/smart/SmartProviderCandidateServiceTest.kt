package tv.own.owntv.smart

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.SmartProviderDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ChannelProviderCandidateEntity
import tv.own.owntv.player.PlayerFailureReason
import tv.own.owntv.smart.SmartProviderCandidateService.ProviderFailureReason

/**
 * Phase 3's contract, which is four rules rather than four features:
 *
 *  1. identity comes from the anchor's provider id, never from a Room row id;
 *  2. the pool is the *other* playlists, minus what the profile hides — and the anchor is never
 *     tested against that hidden set, because the anchor is not a candidate;
 *  3. what gets remembered is the analyzer's own answer: its order, one row per provider, and with
 *     the stamps a previous discovery recorded left intact;
 *  4. an outcome is not a relationship, so a success or a failure can only ever update a row that a
 *     discovery already wrote.
 *
 * The seams are lambdas and the DAO is a hand-written fake of its seven methods, because
 * `app/src/test` has no mocking framework — the same shape `LiveZapListTest` is written in. Nothing
 * here needs a database, a player or a profile store.
 */
class SmartProviderCandidateServiceTest {

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

    /** "BBC One" normalizes to "bbc 1", so an HD-suffixed spelling reaches it too. */
    private val anchor = channel(id = 1, sourceId = 1, remoteId = "101", name = "BBC One")

    /** A row as a previous discovery would have written it: filed under [anchor]'s key. */
    private fun discovered(sourceId: Long, remoteId: String? = null) =
        ChannelProviderCandidateEntity(
            anchorKey = "1:LIVE:101",
            anchorSourceId = 1,
            sourceId = sourceId,
            candidateRemoteId = remoteId,
            candidateName = "BBC One",
        )

    // ---- 1. identity comes from the anchor, never from the row --------------------------------

    @Test
    fun `candidates are filed under the anchor's provider key`() = runBlocking {
        val h = Harness(pool = listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")))

        val result = h.service.discover(anchor)

        assertEquals("1:LIVE:101", result.anchorKey)
        assertFalse(result.skipped)
        assertEquals(1, result.candidates.size)
        val row = h.dao.persisted.single()
        assertEquals("1:LIVE:101", row.anchorKey)
        // The playlist it came from is kept beside the key, because the key alone cannot be resolved
        // back to a source without parsing it.
        assertEquals(1L, row.anchorSourceId)
        assertEquals(2L, row.sourceId)
        assertEquals("77", row.candidateRemoteId)
        assertEquals("BBC One", row.candidateName)
    }

    @Test
    fun `the Room row id takes no part in the key`() = runBlocking {
        val h = Harness(pool = listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")))

        val first = h.service.discover(channel(id = 5, sourceId = 1, remoteId = "101", name = "BBC One"))
        val last = h.service.discover(channel(id = 900, sourceId = 1, remoteId = "202", name = "BBC One"))

        // One playlist, two provider ids: two relationships, whatever the database thinks the row is.
        assertEquals("1:LIVE:101", first.anchorKey)
        assertEquals("1:LIVE:202", last.anchorKey)
    }

    @Test
    fun `an anchor without a provider id does nothing at all`() = runBlocking {
        val h = Harness(pool = listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")))

        // The same name every time, and no provider id — all an id-less M3U row has to offer. Neither
        // is an identity: the name is only the analyzer's de-duplication detail, and `id` is a Room
        // row id that a re-sync may hand to a different channel.
        val idLess = listOf(
            channel(id = 1, sourceId = 1, remoteId = null, name = "BBC One"),
            channel(id = 1, sourceId = 1, remoteId = "", name = "BBC One"),
            channel(id = 1, sourceId = 1, remoteId = "   ", name = "BBC One"),
        )

        for (noId in idLess) {
            val result = h.service.discover(noId)

            assertNull(result.anchorKey)
            assertTrue(result.skipped)
            assertTrue(result.candidates.isEmpty())
        }

        // Not merely "wrote nothing": it never looked. A no-op that reads first would still cost a
        // full channel scan on every tune.
        assertEquals(0, h.sourceIdReads)
        assertEquals(0, h.hiddenReads)
        assertEquals(0, h.poolReads)
        assertEquals(0, h.dao.anchorReads)
        assertTrue(h.dao.writes.isEmpty())
    }

    // ---- 2. the candidate pool -----------------------------------------------------------------

    @Test
    fun `the anchor's own playlist is never part of the pool`() = runBlocking {
        val h = Harness(
            sourceIds = listOf(1L, 2L),
            pool = listOf(
                // A second row of the anchor's *own* provider, carrying the same name. Both the source
                // list and the pool contain it, so only the exclusion can keep it out.
                channel(sourceId = 1, remoteId = "999", name = "BBC One"),
                channel(sourceId = 2, remoteId = "77", name = "BBC One"),
            ),
        )

        val result = h.service.discover(anchor)

        assertEquals(listOf(2L), h.poolSourceIds)
        assertEquals(listOf(2L), result.candidates.map { it.sourceId })
    }

    @Test
    fun `one configured playlist is a no-op rather than a self-comparison`() = runBlocking {
        val h = Harness(
            sourceIds = listOf(1L),
            pool = listOf(channel(sourceId = 1, remoteId = "999", name = "BBC One")),
        )

        val result = h.service.discover(anchor)

        assertFalse(result.skipped)
        assertTrue(result.candidates.isEmpty())
        assertEquals(0, h.poolReads)
        assertTrue(h.dao.writes.isEmpty())
    }

    @Test
    fun `a hidden candidate is not remembered, and a hidden anchor still discovers`() = runBlocking {
        val hiddenCandidate = channel(sourceId = 3, remoteId = "88", name = "BBC One")
        val h = Harness(
            pool = listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One"), hiddenCandidate),
            hidden = setOf(CustomizeKeys.channel(hiddenCandidate), CustomizeKeys.channel(anchor)),
        )

        val result = h.service.discover(anchor)

        // The profile hides that candidate, so it is neither offered nor stored...
        assertEquals(listOf(2L), result.candidates.map { it.sourceId })
        assertEquals(listOf(2L), h.dao.persisted.map { it.sourceId })
        // ...and hiding the anchor itself changes nothing, because the anchor is the channel being
        // looked up, not something on offer. Hiding what failed must not disable its failover.
        assertEquals("1:LIVE:101", result.anchorKey)
        assertFalse(result.skipped)
    }

    @Test
    fun `a pass reads the pool once and stays bounded`() = runBlocking {
        val h = Harness(
            sourceIds = (1L..8L).toList(),
            pool = (2L..8L).map { channel(sourceId = it, remoteId = "$it", name = "BBC One") },
        )

        val result = h.service.discover(anchor, limit = 3)

        assertEquals(1, h.poolReads)
        assertEquals(listOf(2L, 3L, 4L), result.candidates.map { it.sourceId })
        assertEquals(listOf(2L, 3L, 4L), h.dao.persisted.map { it.sourceId })
        // The read carries its own bound too, so a pathological playlist cannot turn a single
        // discovery into an unbounded scan.
        assertTrue("the pool read must be bounded, got ${h.poolLimit}", h.poolLimit in 1..1_000_000)
        // And with no opinion from the caller, a pass is still bounded.
        assertTrue(SmartProviderCandidateService.DEFAULT_LIMIT < Int.MAX_VALUE)
    }

    // ---- 3. what gets remembered -----------------------------------------------------------------

    @Test
    fun `the analyzer's order is preserved, and never re-sorted`() = runBlocking {
        val pool = listOf(
            // Both score exactly 1.0, so nothing that sorts by score — or that falls back on the
            // source id — can produce the right answer. Only the match tier decides, and provider 5's
            // name is identical where provider 2's merely differs in case.
            channel(sourceId = 2, remoteId = "2", name = "bbc one"),
            channel(sourceId = 5, remoteId = "5", name = "BBC One"),
        )
        val h = Harness(sourceIds = listOf(1L, 2L, 5L), pool = pool)

        val result = h.service.discover(anchor)

        assertEquals(listOf(5L, 2L), result.candidates.map { it.sourceId })
        // And it is the analyzer's answer, field for field: this service adds persistence, not
        // matching, so the two can never drift apart.
        assertEquals(
            SmartProviderAnalyzer.analyze(anchor, pool).map { it.sourceId to it.remoteId },
            result.candidates.map { it.sourceId to it.remoteId },
        )
        assertEquals(result.candidates.map { it.sourceId }, h.dao.persisted.map { it.sourceId })
    }

    @Test
    fun `the same pool in a different order is remembered the same way`() = runBlocking {
        val pool = listOf(
            channel(sourceId = 4, remoteId = "4", name = "BBC One"),
            channel(sourceId = 2, remoteId = "2", name = "BBC One"),
            channel(sourceId = 3, remoteId = "3", name = "bbc one"),
        )
        val permutations = listOf(pool, pool.reversed(), listOf(pool[1], pool[2], pool[0]))

        val remembered = permutations.map { ordered ->
            Harness(sourceIds = listOf(1L, 2L, 3L, 4L), pool = ordered).let { h ->
                h.service.discover(anchor)
                h.dao.persisted.map { it.sourceId to it.candidateRemoteId }
            }
        }

        assertEquals(3, remembered.first().size)
        for (got in remembered) {
            assertEquals("input order must not reach the output", remembered.first(), got)
        }
    }

    @Test
    fun `one row is remembered per provider, even when it lists two matching rows`() = runBlocking {
        val pool = listOf(
            // One provider listing the same channel twice: once with an id and once without.
            channel(sourceId = 2, remoteId = null, name = "BBC One"),
            channel(sourceId = 2, remoteId = "77", name = "BBC One"),
        )
        val h = Harness(pool = pool)

        val result = h.service.discover(anchor)

        // The analyzer keeps both, because it keys on the row's own identity...
        assertEquals(2, SmartProviderAnalyzer.analyze(anchor, pool).size)
        // ...but a remembered relationship is per *provider*, which is what the table's key says.
        assertEquals(1, result.candidates.size)
        assertEquals(1, h.dao.persisted.size)
        // The id-bearing row wins, because the analyzer orders it first — and it is the one a later
        // lookup can still address by provider id.
        assertEquals("77", h.dao.persisted.single().candidateRemoteId)
    }

    @Test
    fun `a rediscovery refreshes the match and keeps the stamps`() = runBlocking {
        val dao = RecordingDao(
            listOf(
                ChannelProviderCandidateEntity(
                    anchorKey = "1:LIVE:101",
                    anchorSourceId = 1,
                    sourceId = 2,
                    candidateRemoteId = "77",
                    candidateName = "BBC One",
                    lastSuccessAt = 1_700_000_000_000,
                    lastFailureAt = 1_700_000_001_000,
                    lastFailureReason = "HTTP_403",
                ),
            ),
        )
        val h = Harness(
            pool = listOf(channel(sourceId = 2, remoteId = "78", name = "BBC One HD")),
            dao = dao,
        )

        h.service.discover(anchor)

        val row = dao.find("1:LIVE:101", 2L)!!
        // What a fresh match is authoritative about: the provider's current spelling of the channel.
        assertEquals("78", row.candidateRemoteId)
        assertEquals("BBC One HD", row.candidateName)
        // What it is not: the history. The store's upsert REPLACEs, so without carrying these over the
        // stamps would reset to 0 on every rediscovery and the table would forget everything learned.
        assertEquals(1_700_000_000_000, row.lastSuccessAt)
        assertEquals(1_700_000_001_000, row.lastFailureAt)
        assertEquals("HTTP_403", row.lastFailureReason)
    }

    @Test
    fun `what was discovered is what is read back`() = runBlocking {
        val h = Harness(pool = listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")))

        h.service.discover(anchor)
        val remembered = h.service.candidatesFor(anchor)

        assertEquals(listOf(2L), remembered.map { it.sourceId })
        assertEquals("1:LIVE:101", remembered.single().anchorKey)
        // Two reads: one to carry the stamps over, one for the caller.
        assertEquals(2, h.dao.anchorReads)
    }

    @Test
    fun `a pass that matches nothing reports the key and writes nothing`() = runBlocking {
        val h = Harness(
            pool = listOf(channel(sourceId = 2, remoteId = "77", name = "Discovery Channel")),
        )

        val result = h.service.discover(anchor)

        assertEquals("1:LIVE:101", result.anchorKey)
        assertFalse(result.skipped)
        assertTrue(result.candidates.isEmpty())
        assertTrue(h.dao.writes.isEmpty())
        // Nothing matched, so there was nothing to reconcile a stamp against either.
        assertEquals(0, h.dao.anchorReads)
    }

    @Test
    fun `a non-positive limit is a no-op`() = runBlocking {
        val h = Harness(pool = listOf(channel(sourceId = 2, remoteId = "77", name = "BBC One")))

        for (limit in listOf(0, -1)) {
            val result = h.service.discover(anchor, limit = limit)

            assertEquals("1:LIVE:101", result.anchorKey)
            assertTrue(result.candidates.isEmpty())
        }

        assertEquals(0, h.poolReads)
        assertTrue(h.dao.writes.isEmpty())
    }

    // ---- 4. the two stamps ---------------------------------------------------------------------

    @Test
    fun `a success and a failure are recorded independently`() = runBlocking {
        val dao = RecordingDao(listOf(discovered(sourceId = 2, remoteId = "77")))
        val h = Harness(dao = dao)

        h.service.recordSuccess(anchor, sourceId = 2, at = 1_700_000_000_000)
        h.service.recordFailure(
            anchor,
            sourceId = 2,
            at = 1_700_000_001_000,
            reason = ProviderFailureReason.HTTP_403,
        )

        val row = dao.find("1:LIVE:101", 2L)!!
        // Both survive: "worked in March, failed this morning" is the state a failover wants, and one
        // stamp erasing the other would hide a provider that is intermittently bad.
        assertEquals(1_700_000_000_000, row.lastSuccessAt)
        assertEquals(1_700_000_001_000, row.lastFailureAt)
        // The player's own reason name is what lands in the column, which is what the store's
        // contract says it writes and what a later build must read back by.
        assertEquals(PlayerFailureReason.HTTP_403.name, row.lastFailureReason)
    }

    @Test
    fun `a reason that is about this device is not representable`() {
        val storable = ProviderFailureReason.entries.map { it.player.name }.toSet()

        assertEquals(
            setOf(
                "HTTP_400",
                "HTTP_401",
                "HTTP_403",
                "HTTP_404",
                "HTTP_509",
                "ONE_SESSION_PROVIDER",
                // The provider's own certificate: a fact about the playlist's host, not about this
                // device — every profile that shares the playlist meets the same one.
                "SSL",
            ),
            storable,
        )
        // The enum *is* the provider-relevance filter: there is no way to write a row that condemns a
        // provider for a decoder, an audio downmix or a fallback path, so those device-local facts
        // cannot follow the provider onto every other profile.
        for (deviceLocal in listOf("DECODER_BUSY", "UNSUPPORTED_VIDEO", "AUDIO", "LIVE_FALLBACK")) {
            assertFalse("$deviceLocal is about this device, not the provider", deviceLocal in storable)
        }
    }

    @Test
    fun `an outcome never invents a relationship`() = runBlocking {
        val dao = RecordingDao()
        val h = Harness(dao = dao)

        h.service.recordSuccess(anchor, sourceId = 2, at = 1)
        h.service.recordFailure(anchor, sourceId = 2, at = 2, reason = ProviderFailureReason.HTTP_401)

        // Both were passed through to the store, and both are UPDATEs of a row that does not exist:
        // an outcome is not evidence that this provider ever carried this channel.
        assertEquals(1, dao.failures.size)
        assertTrue(dao.writes.isEmpty())
        assertNull(dao.find("1:LIVE:101", 2L))
    }

    @Test
    fun `forgetting one provider leaves the anchor's others alone`() = runBlocking {
        val dao = RecordingDao(
            listOf(discovered(sourceId = 2, remoteId = "2"), discovered(sourceId = 3, remoteId = "3")),
        )
        val h = Harness(dao = dao)

        h.service.forget(anchor, sourceId = 2)

        assertEquals(listOf("1:LIVE:101" to 2L), dao.forgotten)
        assertEquals(listOf(3L), h.service.candidatesFor(anchor).map { it.sourceId })
    }

    // ---- 5. an anchor with no identity reads and writes nothing ---------------------------------

    @Test
    fun `every entry point is a no-op for an anchor with no provider id`() = runBlocking {
        val dao = RecordingDao(listOf(discovered(sourceId = 2, remoteId = "77")))
        val h = Harness(dao = dao)
        val idLess = channel(id = 1, sourceId = 1, remoteId = null, name = "BBC One")

        assertTrue(h.service.candidatesFor(idLess).isEmpty())
        h.service.recordSuccess(idLess, sourceId = 2, at = 1)
        h.service.recordFailure(idLess, sourceId = 2, at = 2, reason = ProviderFailureReason.HTTP_404)
        h.service.forget(idLess, sourceId = 2)

        // Nothing was asked of the store: there is no key such an anchor could have been filed under,
        // so every one of these would have been a guess about which channel was meant.
        assertEquals(0, dao.anchorReads)
        assertTrue(dao.failures.isEmpty())
        assertTrue(dao.forgotten.isEmpty())
        assertTrue(dao.writes.isEmpty())
    }
}

/**
 * The feature's own persistence interface, hand-written. Seven methods is the whole of it, which is
 * why this is the one collaborator that is a real fake instead of a lambda.
 *
 * Its behaviour follows the SQL: `upsert` replaces the `(anchorKey, sourceId)` row, both stamps are
 * UPDATEs that insert nothing, and `forAnchor` orders by `lastSuccessAt DESC, sourceId ASC`.
 */
private class RecordingDao(
    initial: List<ChannelProviderCandidateEntity> = emptyList(),
) : SmartProviderDao {

    private val rows = initial.toMutableList()

    /** Every batch handed to a write method, in call order. Empty means nothing was persisted. */
    val writes = mutableListOf<List<ChannelProviderCandidateEntity>>()
    val persisted: List<ChannelProviderCandidateEntity> get() = writes.flatten()

    var anchorReads = 0
        private set
    val failures = mutableListOf<Triple<Long, Long, String?>>()
    val forgotten = mutableListOf<Pair<String, Long>>()

    override suspend fun upsert(candidate: ChannelProviderCandidateEntity) {
        writes += listOf(candidate)
        replace(candidate)
    }

    override suspend fun upsertAll(candidates: List<ChannelProviderCandidateEntity>) {
        writes += candidates
        candidates.forEach { replace(it) }
    }

    override suspend fun forAnchor(anchorKey: String): List<ChannelProviderCandidateEntity> {
        anchorReads++
        return rows.filter { it.anchorKey == anchorKey }
            .sortedWith(
                compareByDescending<ChannelProviderCandidateEntity> { it.lastSuccessAt }
                    .thenBy { it.sourceId },
            )
    }

    override suspend fun find(anchorKey: String, sourceId: Long): ChannelProviderCandidateEntity? =
        rows.firstOrNull { it.anchorKey == anchorKey && it.sourceId == sourceId }

    override suspend fun markSuccess(anchorKey: String, sourceId: Long, at: Long) {
        update(anchorKey, sourceId) { it.copy(lastSuccessAt = at) }
    }

    override suspend fun markFailure(anchorKey: String, sourceId: Long, at: Long, reason: String?) {
        failures += Triple(sourceId, at, reason)
        update(anchorKey, sourceId) { it.copy(lastFailureAt = at, lastFailureReason = reason) }
    }

    override suspend fun forget(anchorKey: String, sourceId: Long) {
        forgotten += anchorKey to sourceId
        rows.removeAll { it.anchorKey == anchorKey && it.sourceId == sourceId }
    }

    private fun replace(candidate: ChannelProviderCandidateEntity) {
        rows.removeAll { it.anchorKey == candidate.anchorKey && it.sourceId == candidate.sourceId }
        rows += candidate
    }

    /** An UPDATE, never an INSERT: a stamp on an unknown pairing changes nothing. */
    private fun update(
        anchorKey: String,
        sourceId: Long,
        change: (ChannelProviderCandidateEntity) -> ChannelProviderCandidateEntity,
    ) {
        val at = rows.indexOfFirst { it.anchorKey == anchorKey && it.sourceId == sourceId }
        if (at >= 0) rows[at] = change(rows[at])
    }
}

/**
 * A service and the seams it was built with. The seams are the shape production passes — plain
 * suspending lambdas — so a test supplies behaviour instead of standing up a database, and the
 * counters let a test assert that a no-op really did not look.
 */
private class Harness(
    /**
     * `SourceDao.allSourceIds()`. The pool read below mirrors the DAO's `WHERE source_id IN (...)`,
     * so a test whose pool spans playlists outside this list has to name them here (or its rows are
     * selected away, exactly as a real query would).
     */
    sourceIds: List<Long> = listOf(1L, 2L, 3L),
    pool: List<ChannelEntity> = emptyList(),
    hidden: Set<String> = emptySet(),
    val dao: RecordingDao = RecordingDao(),
) {
    var sourceIdReads = 0
        private set
    var poolReads = 0
        private set
    var hiddenReads = 0
        private set
    var poolLimit = -1
        private set
    var poolSourceIds: List<Long> = emptyList()
        private set

    val service = SmartProviderCandidateService(
        smartProviderDao = dao,
        allSourceIds = {
            sourceIdReads++
            sourceIds
        },
        // Mirrors `ChannelDao.allForSources`: the pool is whatever the source ids select, and the
        // limit is the bound the query itself carries.
        channelsForSources = { ids, limit ->
            poolReads++
            poolSourceIds = ids
            poolLimit = limit
            pool.filter { it.sourceId in ids }
        },
        hiddenItemKeys = {
            hiddenReads++
            hidden
        },
    )
}
