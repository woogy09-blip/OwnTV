package tv.own.owntv.smart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.SmartProviderDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ChannelProviderCandidateEntity

/**
 * Phase 4's contract, which is three answers rather than a failover policy:
 *
 *  1. a candidate is the *row on its own playlist*, resolved by the provider's own id — never by a Room
 *     row id, and never by re-matching a name;
 *  2. a candidate that cannot be used right now is skipped rather than deleted: already spent in this
 *     walk, no provider id to resolve, no row on the playlist any more, or a row the active profile
 *     would not be shown;
 *  3. a verdict is remembered only when it was about the *provider*, and only ever on a relationship a
 *     discovery already wrote.
 *
 * What the player does with those answers — how many playlists a tune may try, the shared deadline, the
 * give-up — is `player-core`'s and is pinned by `LiveTuneControllerTest`. Nothing here needs a database,
 * a player or a profile store: the DAO is a hand-written fake and the seams are the plain suspending
 * lambdas production passes, the shape `SmartProviderCandidateServiceTest` is written in.
 */
class SmartProviderFallbackTest {

    private fun channel(
        id: Long = 0,
        sourceId: Long,
        remoteId: String?,
        name: String,
    ) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = name,
        streamUrl = "http://example.invalid/$sourceId/${remoteId ?: "-"}.ts",
        remoteId = remoteId,
    )

    /** The channel the user is watching, on playlist 1 — the only key a relationship is filed under. */
    private val anchor = channel(id = 1, sourceId = 1, remoteId = "101", name = "BBC One")

    /** A relationship as phase 3 would have written it, filed under [anchor]'s key. */
    private fun remembered(sourceId: Long, remoteId: String?, successAt: Long = 0) =
        ChannelProviderCandidateEntity(
            anchorKey = "1:LIVE:101",
            anchorSourceId = 1,
            sourceId = sourceId,
            candidateRemoteId = remoteId,
            candidateName = "BBC One",
            lastSuccessAt = successAt,
        )

    private class Harness(
        remembered: List<ChannelProviderCandidateEntity> = emptyList(),
        /** The rows the playlists actually hold, keyed by the stable key a candidate is resolved with. */
        rows: Map<Pair<Long, String>, ChannelEntity> = emptyMap(),
        /** Playlists whose Live section is on *and* whose rows the active profile may be shown. */
        visibleSourceIds: Set<Long> = setOf(1L, 2L, 3L, 4L),
        clock: Long = CLOCK,
    ) {
        val dao = StampingDao(remembered)
        val lookups = mutableListOf<Pair<Long, String>>()
        val visibilityChecks = mutableListOf<ChannelEntity>()

        val fallback = SmartProviderFallback(
            candidates = SmartProviderCandidateService(
                smartProviderDao = dao,
                // Discovery's own seams are unused here: this test never asks for a match.
                allSourceIds = { emptyList() },
                channelsForSources = { _, _ -> emptyList() },
                hiddenItemKeys = { emptySet() },
            ),
            channelByRemote = { sourceId, remoteId ->
                lookups += sourceId to remoteId
                rows[sourceId to remoteId]
            },
            isVisible = { channel ->
                visibilityChecks += channel
                channel.sourceId in visibleSourceIds
            },
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            nowMs = { clock },
        )
    }

    // ---- 1. a candidate is a row on its own playlist ------------------------------------------

    @Test
    fun `a candidate comes back as its own row, resolved by the provider's id`() = runBlocking {
        val substitute = channel(id = 55, sourceId = 2, remoteId = "77", name = "BBC One")
        val h = Harness(
            remembered = listOf(remembered(sourceId = 2, remoteId = "77")),
            rows = mapOf((2L to "77") to substitute),
        )

        val next = h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L))

        assertEquals(substitute, next)
        // The candidate's provider id is what was looked up — the row id it happens to have is not.
        assertEquals(listOf(2L to "77"), h.lookups)
        assertEquals(listOf(substitute), h.visibilityChecks)
    }

    @Test
    fun `an anchor with no provider id has nothing to look up`() = runBlocking {
        val h = Harness(remembered = listOf(remembered(sourceId = 2, remoteId = "77")))
        val unnamed = channel(id = 2, sourceId = 1, remoteId = null, name = "BBC One")

        assertNull(h.fallback.nextCandidate(unnamed, triedSourceIds = setOf(1L)))
        assertEquals("nothing may be read for an anchor that could never have been filed", 0, h.dao.anchorReads)
    }

    // ---- 2. what cannot be used right now is skipped, not deleted ------------------------------

    @Test
    fun `a playlist already spent in this walk is skipped`() = runBlocking {
        val first = channel(id = 21, sourceId = 2, remoteId = "77", name = "BBC One")
        val second = channel(id = 31, sourceId = 3, remoteId = "88", name = "BBC One")
        val h = Harness(
            remembered = listOf(
                remembered(sourceId = 2, remoteId = "77"),
                remembered(sourceId = 3, remoteId = "88"),
            ),
            rows = mapOf((2L to "77") to first, (3L to "88") to second),
        )

        val next = h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L, 2L))

        assertEquals(second, next)
        assertEquals("a spent playlist is not even looked up", listOf(3L to "88"), h.lookups)
    }

    @Test
    fun `the memory's own order decides, not the order the playlists are configured in`() = runBlocking {
        val proven = channel(id = 41, sourceId = 4, remoteId = "44", name = "BBC One")
        val h = Harness(
            // Written in playlist order, read back most-recently-proven-first, which is the DAO's order.
            remembered = listOf(
                remembered(sourceId = 2, remoteId = "22"),
                remembered(sourceId = 4, remoteId = "44", successAt = 5_000),
            ),
            rows = mapOf(
                (2L to "22") to channel(id = 21, sourceId = 2, remoteId = "22", name = "BBC One"),
                (4L to "44") to proven,
            ),
        )

        assertEquals(proven, h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L)))
        assertEquals(1, h.dao.anchorReads)
    }

    @Test
    fun `a candidate whose provider gave no id is skipped`() = runBlocking {
        val named = channel(id = 61, sourceId = 3, remoteId = "88", name = "BBC One")
        val h = Harness(
            remembered = listOf(
                remembered(sourceId = 2, remoteId = null),
                remembered(sourceId = 2, remoteId = "   "),
                remembered(sourceId = 3, remoteId = "88"),
            ),
            rows = mapOf((3L to "88") to named),
        )

        assertEquals(named, h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L)))
        assertEquals("only the id-bearing row is looked up", listOf(3L to "88"), h.lookups)
    }

    @Test
    fun `a candidate the playlist no longer holds is skipped`() = runBlocking {
        val present = channel(id = 71, sourceId = 3, remoteId = "88", name = "BBC One")
        val h = Harness(
            remembered = listOf(
                remembered(sourceId = 2, remoteId = "77"),
                remembered(sourceId = 3, remoteId = "88"),
            ),
            // The memory outlived a re-sync that removed playlist 2's row.
            rows = mapOf((3L to "88") to present),
        )

        assertEquals(present, h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L)))
        assertEquals(listOf(2L to "77", 3L to "88"), h.lookups)
    }

    @Test
    fun `a candidate the active profile would not be shown is skipped`() = runBlocking {
        val allowed = channel(id = 81, sourceId = 3, remoteId = "88", name = "BBC One")
        val h = Harness(
            remembered = listOf(
                remembered(sourceId = 2, remoteId = "77"),
                remembered(sourceId = 3, remoteId = "88"),
            ),
            rows = mapOf(
                (2L to "77") to channel(id = 51, sourceId = 2, remoteId = "77", name = "BBC One"),
                (3L to "88") to allowed,
            ),
            visibleSourceIds = setOf(1L, 3L), // playlist 2's Live section is off / hidden
        )

        assertEquals(allowed, h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L)))
        assertEquals(2, h.visibilityChecks.size)
    }

    @Test
    fun `no usable candidate answers null rather than the anchor`() = runBlocking {
        val h = Harness(remembered = emptyList())

        assertNull(h.fallback.nextCandidate(anchor, triedSourceIds = setOf(1L)))
        assertEquals("the memory was consulted", 1, h.dao.anchorReads)
        assertTrue(h.lookups.isEmpty())
    }

    // ---- 3. a verdict is about the provider, and lands on a relationship -----------------------

    /** The candidate's row, as the controller hands it to both verdicts: the row it actually tuned. */
    private val substitute = channel(id = 91, sourceId = 2, remoteId = "77", name = "BBC One")

    @Test
    fun `a picture is stamped on the playlist that produced it`() {
        val h = Harness()

        h.fallback.onOpened(anchor, substitute)

        assertEquals(listOf(Triple("1:LIVE:101", 2L, CLOCK)), h.dao.successes)
        assertEquals("a success says nothing about failing", 0, h.dao.failures.size)
    }

    @Test
    fun `a provider verdict is remembered as the player's own reason name`() {
        val h = Harness()

        h.fallback.onFailed(anchor, substitute, "mpv couldn't play it: HTTP error 403")

        assertEquals(listOf(Triple("1:LIVE:101", 2L, "HTTP_403")), h.dao.failures)
        assertEquals("a failure says nothing about working", 0, h.dao.successes.size)
    }

    @Test
    fun `a certificate the provider could not present is an SSL verdict`() {
        val h = Harness()

        h.fallback.onFailed(anchor, substitute, "ExoPlayer gave up: certificate verify failed")

        assertEquals(listOf(Triple("1:LIVE:101", 2L, "SSL")), h.dao.failures)
    }

    @Test
    fun `a one-session refusal is the provider's own verdict`() {
        val h = Harness()

        h.fallback.onFailed(anchor, substitute, "ExoPlayer gave up: response code 458")

        assertEquals(listOf(Triple("1:LIVE:101", 2L, "ONE_SESSION_PROVIDER")), h.dao.failures)
    }

    @Test
    fun `nothing this device did is held against the playlist`() {
        val h = Harness()

        // A decoder the phone cannot start, a format its mpv refuses, and a window that simply expired:
        // none of them is a fact about the playlist, and none may follow it into another profile.
        h.fallback.onFailed(anchor, substitute, "mpv couldn't play it: err -12")
        h.fallback.onFailed(anchor, substitute, "no picture within 30s of tuning")
        h.fallback.onFailed(anchor, substitute, "mpv never opened it (35s, no picture and no error)")
        h.fallback.onFailed(anchor, substitute, "mpv couldn't play it: unrecognized file format")

        assertTrue("no provider verdict, no stamp", h.dao.failures.isEmpty())
    }
}

/** The clock both verdicts are stamped with, so no assertion races a real one. */
private const val CLOCK = 1_700_000_000_000L

/**
 * A `SmartProviderDao` that keeps its rows in memory and records the stamps, so a test can assert what
 * was written rather than that *something* was. An UPDATE, never an INSERT, exactly like the real DAO: a
 * verdict on a pairing no discovery wrote changes nothing.
 */
private class StampingDao(initial: List<ChannelProviderCandidateEntity>) : SmartProviderDao {
    private val rows = initial.toMutableList()

    val successes = mutableListOf<Triple<String, Long, Long>>()
    val failures = mutableListOf<Triple<String, Long, String?>>()
    var anchorReads = 0
        private set

    override suspend fun upsert(candidate: ChannelProviderCandidateEntity) {
        rows += candidate
    }

    override suspend fun upsertAll(candidates: List<ChannelProviderCandidateEntity>) {
        rows += candidates
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
        successes += Triple(anchorKey, sourceId, at)
        stamp(anchorKey, sourceId) { it.copy(lastSuccessAt = at) }
    }

    override suspend fun markFailure(anchorKey: String, sourceId: Long, at: Long, reason: String?) {
        failures += Triple(anchorKey, sourceId, reason)
        stamp(anchorKey, sourceId) { it.copy(lastFailureAt = at, lastFailureReason = reason) }
    }

    override suspend fun forget(anchorKey: String, sourceId: Long) {
        rows.removeAll { it.anchorKey == anchorKey && it.sourceId == sourceId }
    }

    private fun stamp(
        anchorKey: String,
        sourceId: Long,
        change: (ChannelProviderCandidateEntity) -> ChannelProviderCandidateEntity,
    ) {
        val index = rows.indexOfFirst { it.anchorKey == anchorKey && it.sourceId == sourceId }
        if (index >= 0) rows[index] = change(rows[index])
    }
}
