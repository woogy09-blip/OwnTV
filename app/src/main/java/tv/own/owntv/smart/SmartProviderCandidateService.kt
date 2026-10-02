package tv.own.owntv.smart

import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.SmartProviderDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ChannelProviderCandidateEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.player.PlayerFailureReason
import tv.own.owntv.smart.SmartProviderAnalyzer.ChannelCandidate

/**
 * Smart Provider Engine — **Phase 3**: the on-demand pass that *remembers* what Phase 1 found.
 *
 * Phase 1 ([SmartProviderAnalyzer]) answers "where else can this channel be watched" in memory and
 * writes nothing. Phase 2 added the table that keeps the answer ([SmartProviderDao], core, v47: one
 * row per channel-and-provider relationship, carrying a success and a failure stamp). This class is
 * the join between them — read the pool, ask the analyzer, persist what it found — and it runs only
 * when it is asked to. There is no scheduler, no probe, no retry and no failover decision here.
 *
 * ### It lives in the app, because the two halves do
 * Phase 1 is app-local and Phase 2 is core-local, so neither module can host the join: core cannot
 * see the analyzer, and the analyzer is the app's. Nothing new is introduced to bridge them — this
 * composes [SmartProviderDao] with `ChannelDao`, `SourceDao`, [SmartProviderAnalyzer] and
 * `CustomizationStore`, every one of which already exists.
 *
 * ### Why two of the DAOs arrive as functions
 * The app has no mocking framework and no hand-written fakes; every collaborator it injects so a
 * test can supply its own is a function, which is how `LiveZapList` reaches `ChannelDao`.
 * `ChannelDao` declares 67 methods and `SourceDao` 27, and this class calls exactly one of each —
 * `allSourceIds` and `allForSources` — so taking the interfaces as parameters would make every test
 * stub 94 methods to reach one. The seams below are those two single methods, named as their DAOs
 * name them. [SmartProviderDao] is taken as itself: its seven methods *are* this feature's
 * persistence.
 *
 * ### Identity is the anchor's, and only when the provider gave one
 * A relationship is keyed by the anchor's [enginePinKey] — `sourceId:LIVE:remoteId` — and never by
 * [ChannelEntity.id], which is a local row id a re-sync is free to hand to a different row. An
 * anchor with no `remoteId` therefore has *no* persistent identity at all, and every entry point
 * here becomes a no-op rather than inventing one from the channel name or the row id. The
 * analyzer's own id-less key (`sourceId:#normalizedName`) is a runtime de-duplication key whose
 * spelling it documents as private to itself, so it is deliberately not promoted to a stored key.
 *
 * ### Hidden candidates, and the anchor that never is
 * Only item-level `hiddenItems` is consulted, keyed through [CustomizeKeys.channel] — no `isHidden`
 * column, no hiding table, no category or profile machinery. A hidden *candidate* is not persisted.
 * The anchor is never tested against the hidden set, so hiding the very channel that failed cannot
 * switch discovery off for it.
 */
class SmartProviderCandidateService(
    private val smartProviderDao: SmartProviderDao,
    /** `SourceDao.allSourceIds()` — every configured playlist. */
    private val allSourceIds: suspend () -> List<Long>,
    /** `ChannelDao.allForSources(sourceIds, limit)` — the live pool, id-less M3U rows included. */
    private val channelsForSources: suspend (
        sourceIds: List<Long>,
        limit: Int,
    ) -> List<ChannelEntity>,
    /**
     * The active profile's item-level `hiddenItems` keys for `LIVE`, spelled as
     * [CustomizeKeys.channel] spells them — in production,
     * `CustomizationStore.observe(profileId, MediaType.LIVE).first().hiddenItems.keys`.
     * A seam for the same reason as the DAO reads: the store needs a profile id, and which profile
     * is active is the caller's fact, not this service's.
     */
    private val hiddenItemKeys: suspend () -> Set<String>,
) {
    /**
     * Find and remember every other provider that looks like it carries [anchor].
     *
     * Deterministic and total: the same anchor, pool and hidden set produce the same rows in the
     * same order, every time. That order is the analyzer's own — its match-tier-then-score
     * comparator — because this method never re-sorts. Ordering by `score` alone in particular would
     * lift a fuzzy same-name row above an exact one on another provider.
     *
     * A no-op — nothing read, nothing written, `candidates` empty — when the anchor has no
     * `remoteId` (see the class note on identity), when [limit] is not positive, or when the anchor
     * is the only playlist configured.
     *
     * @param limit how many providers one pass may remember. It is applied after the analyzer has
     *   been asked for everything, because the analyzer's order is what decides *which* row survives
     *   for a provider, and a provider is what is being counted here.
     */
    suspend fun discover(anchor: ChannelEntity, limit: Int = DEFAULT_LIMIT): DiscoveryResult {
        val anchorKey = anchorKeyOf(anchor)
            ?: return DiscoveryResult(anchorKey = null, candidates = emptyList())
        if (limit <= 0) return DiscoveryResult(anchorKey, emptyList())

        // The engine answers "where *else*", so the anchor's own playlist is never candidate pool.
        val otherSourceIds = allSourceIds().filter { it != anchor.sourceId }
        if (otherSourceIds.isEmpty()) return DiscoveryResult(anchorKey, emptyList())

        val hidden = hiddenItemKeys()
        val pool = channelsForSources(otherSourceIds, POOL_LIMIT)
            .filterNot { CustomizeKeys.channel(it) in hidden }

        val ranked = SmartProviderAnalyzer.analyze(anchor, pool)
            // One remembered relationship per provider: the table is keyed on (anchor, provider),
            // so two rows from the same provider are one candidate, not two. The first in the
            // analyzer's order wins, which is what keeps an id-bearing row over an id-less twin.
            .distinctBy { it.sourceId }
            .take(limit)
        if (ranked.isEmpty()) return DiscoveryResult(anchorKey, emptyList())

        // The DAO's upsert REPLACEs, so a rediscovery would otherwise reset both stamps — and the
        // stamps are what the table is for. Carry forward what is already known about a provider and
        // refresh only what a fresh match is authoritative about: its name and its provider id.
        val known = smartProviderDao.forAnchor(anchorKey).associateBy { it.sourceId }
        val remembered = ranked.map { candidate ->
            candidate.toEntity(anchorKey, anchor.sourceId, known[candidate.sourceId])
        }
        smartProviderDao.upsertAll(remembered)

        return DiscoveryResult(anchorKey, ranked)
    }

    /**
     * Every provider already remembered for [anchor], most recently proven first — the DAO's own
     * order, which is deterministic for candidates that tie. Empty, and no query, for an anchor with
     * no `remoteId`: there is nothing such an anchor could have been stored under.
     */
    suspend fun candidatesFor(anchor: ChannelEntity): List<ChannelProviderCandidateEntity> =
        anchorKeyOf(anchor)?.let { smartProviderDao.forAnchor(it) }.orEmpty()

    /**
     * Record that a tune on [sourceId] worked for [anchor] at [at], leaving any recorded failure
     * alone. The two stamps are written independently because "worked in March, failed this morning"
     * is a real state — the one a later failover wants to see — while a single overwritten outcome
     * would erase it. A provider this table has never heard of is not inserted: a success is not
     * evidence of a discovered relationship.
     */
    suspend fun recordSuccess(anchor: ChannelEntity, sourceId: Long, at: Long) {
        val anchorKey = anchorKeyOf(anchor) ?: return
        smartProviderDao.markSuccess(anchorKey, sourceId, at)
    }

    /**
     * Record that [sourceId] failed for [anchor] at [at], because of [reason].
     *
     * [ProviderFailureReason] is the provider-relevance filter the entity asks its caller to own:
     * the only reasons representable are ones where the *provider* returned a verdict, so a decoder
     * complaint or a local audio problem can never condemn a provider for every profile that shares
     * it. The stored text is the player's own reason name.
     */
    suspend fun recordFailure(
        anchor: ChannelEntity,
        sourceId: Long,
        at: Long,
        reason: ProviderFailureReason,
    ) {
        val anchorKey = anchorKeyOf(anchor) ?: return
        smartProviderDao.markFailure(anchorKey, sourceId, at, reason.player.name)
    }

    /** Forget one remembered provider for [anchor], for a match that turned out to be wrong. */
    suspend fun forget(anchor: ChannelEntity, sourceId: Long) {
        val anchorKey = anchorKeyOf(anchor) ?: return
        smartProviderDao.forget(anchorKey, sourceId)
    }

    /**
     * The anchor's persistent identity, or null when it has none to give.
     *
     * `remoteId` reaches [enginePinKey] exactly as the row carries it, because that helper keys every
     * engine pin in the app: normalizing it here would mint a key nothing else could reproduce. It
     * answers null for a null *or* blank id, which is the no-op the class doc describes.
     */
    private fun anchorKeyOf(anchor: ChannelEntity): String? =
        enginePinKey(anchor.sourceId, MediaType.LIVE.name, anchor.remoteId)

    /**
     * One relationship to store, carrying [previous]'s stamps over when this provider is already
     * known for this anchor. Those three columns are the only ones a rediscovery is not
     * authoritative about; the name and the provider id are what it just refreshed.
     */
    private fun ChannelCandidate.toEntity(
        anchorKey: String,
        anchorSourceId: Long,
        previous: ChannelProviderCandidateEntity?,
    ) = ChannelProviderCandidateEntity(
        anchorKey = anchorKey,
        anchorSourceId = anchorSourceId,
        sourceId = sourceId,
        candidateRemoteId = remoteId,
        candidateName = channelName,
        lastSuccessAt = previous?.lastSuccessAt ?: 0,
        lastFailureAt = previous?.lastFailureAt ?: 0,
        lastFailureReason = previous?.lastFailureReason,
    )

    /**
     * What one [discover] pass did: the identity the matches were filed under, and the matches
     * themselves. [anchorKey] is null exactly when the anchor had no identity to file under, which is
     * what tells a caller "nothing could be looked up" apart from "nothing matched".
     */
    data class DiscoveryResult(
        val anchorKey: String?,
        val candidates: List<ChannelCandidate>,
    ) {
        /** True when the anchor had no identity, so the pass read nothing and wrote nothing. */
        val skipped: Boolean get() = anchorKey == null
    }

    /**
     * The provider-relevant subset of [PlayerFailureReason]: the reasons that say something about the
     * *provider* rather than about this device or this build.
     *
     * It exists as an enum so that a caller reaching for `PlayerFailureReason.DECODER_BUSY` or
     * `LIVE_FALLBACK` fails to compile, instead of writing a fact that would then follow the provider
     * into every other profile on the device. [player] is the underlying player reason, and its
     * `name` is the text stored.
     */
    enum class ProviderFailureReason(val player: PlayerFailureReason) {
        /** The provider rejected the request outright. */
        HTTP_400(PlayerFailureReason.HTTP_400),

        /** The provider wants credentials, and the ones presented are wrong or expired. */
        HTTP_401(PlayerFailureReason.HTTP_401),

        /** The provider refuses this account, IP or user agent. */
        HTTP_403(PlayerFailureReason.HTTP_403),

        /** The provider says the stream is gone. */
        HTTP_404(PlayerFailureReason.HTTP_404),

        /** The provider's own session or connection ceiling was reached. */
        HTTP_509(PlayerFailureReason.HTTP_509),

        /** The provider allows one session at a time, and it is in use elsewhere. */
        ONE_SESSION_PROVIDER(PlayerFailureReason.ONE_SESSION_PROVIDER),
    }

    companion object {
        /** Providers one [discover] pass remembers when the caller has no opinion. */
        const val DEFAULT_LIMIT = 50

        /**
         * Upper bound on pool rows read in one pass, mirroring the core EPG matcher's own cap: a scan
         * of this size is what the apps already do, and it keeps a pathological playlist from turning
         * a single discovery into an unbounded read.
         */
        private const val POOL_LIMIT = 20_000
    }
}
