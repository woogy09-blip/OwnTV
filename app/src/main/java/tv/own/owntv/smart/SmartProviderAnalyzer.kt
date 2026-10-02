package tv.own.owntv.smart

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.player.enginePinKey

/**
 * Smart Provider Engine — **Phase 1 only**: find the *same* live channel on other providers.
 *
 * This is deliberately the smallest useful piece of the engine. It reads nothing, writes nothing,
 * opens no connection and keeps no state: hand it an anchor row and a pool of candidate rows and it
 * hands back the same ordered list every time. Deciding what to *do* with that list — scoring a
 * provider, choosing a failover target, remembering the relationship — is a later phase and is
 * explicitly not here.
 *
 * ### Identity is the provider's, never Room's
 * [ChannelEntity.id] is a local row id: two providers carrying the same channel have unrelated ids,
 * and a resync can hand one row's id to a different row. So the engine identifies a channel by the
 * provider-stable composite the rest of the app already uses ([enginePinKey]): **`sourceId` + media
 * type + `remoteId`**. That composite is used for exactly two things —
 *
 *  1. **self-exclusion**, so a channel is never offered as its own failover target, and
 *  2. **de-duplication**, so a provider channel listed twice collapses into one candidate,
 *
 * — and it is reported on every candidate as [ChannelCandidate.identityKey] so a later phase can
 * anchor persistence on the same stable key. When a provider gives no id there is no such key at all
 * and the field is `null`, which means *runtime-only*: never a key to invent from the channel name,
 * never a value two rows may be assumed to share. Note it is deliberately *not* a cross-provider
 * identity either: it contains `sourceId`, so the same channel on two providers is two different
 * keys, and an equal `remoteId` on two sources proves nothing on its own (see
 * [MatchType.SAME_PROVIDER_ID]).
 *
 * ### A candidate is always a different provider
 * The engine answers one question — "where *else* can this channel be watched" — so a candidate's
 * `sourceId` is never the anchor's. A second row on the anchor's own source describes the anchor
 * again, however good its name match, and is dropped for every row in [analyze] rather than left to
 * a caller to filter out.
 *
 * ### Matching reuses the project's matcher rather than inventing one
 * Names are reduced with [EpgMatcher.normalizeForEpg] and scored with [EpgMatcher.scoreNormalized] —
 * the same NFKC / lower-case / noise-stripping normalizer and the same Jaro-Winkler-or-token-overlap
 * scorer the app already uses for guide matching, *including* its embedded channel-number guard, so
 * "Sky Sports 2" can never be proposed for "Sky Sports 3". The tier cut-offs are [EpgMatcher]'s own
 * published thresholds. No new fuzzy-matching rule is introduced, and no `ChannelEntity.streamUrl`,
 * logo or provider credential is ever read, returned or logged.
 */
object SmartProviderAnalyzer {

    /**
     * How a candidate matched, strongest first. [rank] is the primary sort key of the result order.
     */
    enum class MatchType(val rank: Int) {
        /**
         * The anchor's own provider id, seen on a *different* source, **with agreeing names**.
         *
         * An equal id is not evidence on its own — every panel numbers its own channels, so
         * "id 101" is a different channel on almost every provider. This tier therefore only ever
         * *promotes* a candidate whose normalized name already matches; it never manufactures a match
         * out of an id, which is why `unrelated channels` cannot match by sharing a number.
         */
        SAME_PROVIDER_ID(0),

        /** The raw names are identical once trimmed — case and punctuation included. */
        EXACT_NAME(1),

        /** Different raw names that normalize to the same token string, e.g. `BBC-ONE` / `bbc one`. */
        EXACT_NORMALIZED_NAME(2),

        /** [EpgMatcher.AUTO_THRESHOLD] or better — the bar the guide matcher applies unattended. */
        STRONG_NAME(3),

        /**
         * [EpgMatcher.REVIEW_THRESHOLD] or better — plausible, worth a look.
         *
         * This is the weakest tier, and the floor is deliberately that high: [EpgMatcher] caps a pair
         * whose embedded channel numbers *disagree* at 0.60, so a floor above that cap is what makes
         * its "Sky Sports 2 is never Sky Sports 3" guard binding here rather than advisory. The
         * picker's looser `PICKER_SUGGEST_THRESHOLD` band is for ordering a list a human is already
         * looking at, never for proposing a match unattended, so it is deliberately not used.
         */
        REVIEW_NAME(4),
    }

    /**
     * One provider that looks like it carries the anchor channel. Deliberately carries **no stream
     * URL and no logo**: Phase 1 identifies candidates, it does not resolve them.
     */
    data class ChannelCandidate(
        /** 1-based position in the returned order — the rank to show. */
        val rank: Int,
        /** The playlist this candidate lives on — never the anchor's own source. */
        val sourceId: Long,
        /** The provider's id on that source; a blank id is reported as null. */
        val remoteId: String?,
        /** The candidate's channel name exactly as its provider spelled it. */
        val channelName: String,
        val matchType: MatchType,
        /** [EpgMatcher] similarity in `0.0..1.0`; the first three tiers are reported as exactly `1.0`. */
        val score: Double,
        /**
         * `sourceId:LIVE:remoteId` — see [enginePinKey]. **Null means this provider gave no id**, and
         * that is not a value to stand in for: a non-null key is provider-stable and may be persisted
         * as a relationship key, whereas a null one is a *runtime-only* candidate. Never invent a key
         * from the channel name instead, never treat two nulls as the same channel, and never group
         * null keys together.
         */
        val identityKey: String?,
    )

    /**
     * Rank every channel in [candidates] that plausibly *is* [anchor] somewhere else.
     *
     * Pure and total: the same inputs produce the same output, in the same order, regardless of the
     * order [candidates] arrived in. Nothing here reads a clock, a random source, a database, network
     * state or any other mutable state — the only inputs are the two arguments.
     *
     * Rows that cannot be a candidate at all are dropped rather than returned with a low score: a
     * blank anchor name (nothing to match against), every row on the anchor's *own* source (candidates
     * are other providers, so a second listing there is not a failover target), anything below
     * [EpgMatcher.REVIEW_THRESHOLD] — which is what keeps a channel whose *number* differs ("Sky
     * Sports 2" for "Sky Sports 3") out, however alike the letters are — and a repeat of a provider
     * channel already kept (the same provider listing it twice).
     *
     * @param limit maximum candidates to return, strongest first; the default returns all of them.
     */
    fun analyze(
        anchor: ChannelEntity,
        candidates: List<ChannelEntity>,
        limit: Int = Int.MAX_VALUE,
    ): List<ChannelCandidate> {
        val anchorName = anchor.name.trim()
        if (anchorName.isEmpty() || limit <= 0) return emptyList()
        val anchorNorm = EpgMatcher.normalizeForEpg(anchorName)
        val anchorRemoteId = anchor.remoteId?.trim()?.takeIf { it.isNotEmpty() }
        val anchorIdentity = identity(anchor.sourceId, anchorRemoteId, anchorNorm)

        return candidates.asSequence()
            .mapNotNull { score(anchor, anchorName, anchorNorm, anchorRemoteId, anchorIdentity, it) }
            .sortedWith(ORDER)
            .distinctBy { it.identity }
            .take(limit)
            .mapIndexed { index, scored -> scored.toCandidate(index + 1) }
            .toList()
    }

    /**
     * The total order behind "deterministic". Every key is a property of the candidate itself — never
     * its position in the caller's list — so re-ordering the input cannot re-order the output:
     *
     *  1. [MatchType.rank] — how good the *kind* of match is;
     *  2. score, descending — how close the names are within a kind;
     *  3. `sourceId` — a stable, user-meaningful provider order;
     *  4. `remoteId`, nulls last — a provider that names its rows ranks above one that doesn't;
     *  5. the raw name — the final tie-break.
     *
     * Together 3–5 make the order **total** among the survivors of de-duplication: two rows agreeing
     * on all five would share a `sourceId` *and* an identity, so one of them was already dropped.
     */
    private val ORDER: Comparator<Scored> = compareBy<Scored> { it.matchType.rank }
        .thenByDescending { it.score }
        .thenBy { it.sourceId }
        .thenBy(nullsLast<String>()) { it.remoteId }
        .thenBy { it.channelName }

    /**
     * A scored candidate before it is ranked. Carries the computed [identity] so ordering and
     * de-duplication never have to normalize the same name twice.
     */
    private class Scored(
        val identity: String,
        val sourceId: Long,
        val remoteId: String?,
        val channelName: String,
        val matchType: MatchType,
        val score: Double,
        val identityKey: String?,
    ) {
        fun toCandidate(rank: Int) = ChannelCandidate(
            rank = rank,
            sourceId = sourceId,
            remoteId = remoteId,
            channelName = channelName,
            matchType = matchType,
            score = score,
            identityKey = identityKey,
        )
    }

    /**
     * Score one row against the anchor, or null when it is not a candidate at all.
     *
     * Tier first, score second: each tier is only chosen when the evidence for it is actually
     * present — equality for the exact tiers, [EpgMatcher]'s own thresholds for the rest — so a
     * returned [MatchType] is always an honest description of why the row is in the list.
     */
    private fun score(
        anchor: ChannelEntity,
        anchorName: String,
        anchorNorm: String,
        anchorRemoteId: String?,
        anchorIdentity: String,
        candidate: ChannelEntity,
    ): Scored? {
        // A candidate is always a *different* provider. The engine answers "where else can this
        // channel be watched", so a second row on the anchor's own source describes the anchor rather
        // than somewhere to fail over to, and is dropped here — on the one path every row must pass —
        // rather than left to a caller to filter. Deliberately independent of `remoteId`: the same
        // source under a *different* provider id is still the anchor's own provider.
        if (candidate.sourceId == anchor.sourceId) return null

        val name = candidate.name.trim()
        if (name.isEmpty()) return null
        val norm = EpgMatcher.normalizeForEpg(name)
        val remoteId = candidate.remoteId?.trim()?.takeIf { it.isNotEmpty() }
        val candidateIdentity = identity(candidate.sourceId, remoteId, norm)
        // The anchor itself, stated at the identity level. `identity` is `sourceId`-prefixed, so an
        // equal identity implies an equal source — already excluded above; this restates the rule for
        // anything that later widens [identity], and `candidateIdentity` is needed below regardless.
        if (candidateIdentity == anchorIdentity) return null

        val similarity = EpgMatcher.scoreNormalized(anchorNorm, norm)
        val nameMatches = anchorNorm.isNotEmpty() && anchorNorm == norm
        val matchType = when {
            // An equal provider id *and* agreeing names — the closest pairing the engine can report.
            anchorRemoteId != null && anchorRemoteId == remoteId &&
                candidate.sourceId != anchor.sourceId && nameMatches -> MatchType.SAME_PROVIDER_ID

            name == anchorName -> MatchType.EXACT_NAME
            nameMatches -> MatchType.EXACT_NORMALIZED_NAME
            similarity >= EpgMatcher.AUTO_THRESHOLD -> MatchType.STRONG_NAME
            similarity >= EpgMatcher.REVIEW_THRESHOLD -> MatchType.REVIEW_NAME
            else -> return null
        }
        // The three exact-ish tiers are equal by construction; only the fuzzy tiers carry a score.
        val score = if (matchType.rank <= MatchType.EXACT_NORMALIZED_NAME.rank) 1.0 else similarity
        return Scored(
            identity = candidateIdentity,
            sourceId = candidate.sourceId,
            remoteId = remoteId,
            channelName = name,
            matchType = matchType,
            score = score,
            // Channels are always LIVE rows, hence the media type — the same key shape the engine-pin
            // stores already use, so a later phase can persist a candidate against it unchanged.
            identityKey = enginePinKey(candidate.sourceId, MediaType.LIVE.name, remoteId),
        )
    }

    /**
     * The provider-level identity of one row, as a string usable as a map key. **Private to this
     * file**: it backs de-duplication and self-exclusion only, is never persisted, and nothing outside
     * the analyzer may depend on its spelling.
     *
     * A row with a provider id keys on it; a row without one (a hand-made M3U entry, say) falls back
     * to its *normalized* name behind a `#`. That prefix keeps the two shapes apart — a normalized name
     * can never contain `#`, because [EpgMatcher.normalizeForEpg] reduces everything else to letters,
     * digits and spaces — so an id-less row can never impersonate an id-bearing one.
     *
     * **Not the same key as `CustomizeKeys.channel`, and not interchangeable with it.** That helper
     * keys on `"$sourceId:${remoteId ?: name}"` — the *raw* channel name, with no marker and no media
     * type — so for an id-less row on source 1 named `"BBC One HD"` it yields `"1:BBC One HD"`, where
     * this yields `"1:#bbc 1"`. The two are deliberately separate: this key is compared only against
     * another value from this same function, so it is free to follow the matcher. Persisting a
     * relationship is [ChannelCandidate.identityKey]'s job, and that uses the app-wide [enginePinKey]
     * shape (`sourceId:LIVE:remoteId`).
     */
    private fun identity(sourceId: Long, remoteId: String?, normName: String): String =
        "$sourceId:${remoteId ?: "#$normName"}"
}

