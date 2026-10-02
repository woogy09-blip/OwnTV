package tv.own.owntv.smart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.LiveTuneController
import tv.own.owntv.player.PlayerErrors

/**
 * Smart Provider Engine — **Phase 4**: the failover seam `player-core` asks, answered from the memory
 * phase 3 keeps.
 *
 * Phase 3 ([SmartProviderCandidateService]) knows *which* other playlists carry a channel and files the
 * relationship under the anchor's provider key. `LiveTuneController` knows *what to do* when a playlist
 * runs out of engines and formats: one substitute at a time, whole tunes rather than rungs, a shared
 * deadline, and a give-up that is honest about being one. This class is the join between the two, and it
 * is deliberately thin — three answers, no policy:
 *
 *  1. [nextCandidate] — the next playlist's own row for the channel, or null for "nowhere left".
 *  2. [onOpened] — a playlist put a picture on screen.
 *  3. [onFailed] — a playlist ran out of engines and formats.
 *
 * Everything the *player* decides stays in the player: how many playlists a tune may try, when the
 * deadline runs out, and when the channel is declared lost. Everything the *app* decides stays here, and
 * it is only ever "which row, and what was that worth".
 *
 * ### The candidate is a row on its own playlist, resolved by the provider's own id
 * A remembered relationship carries the candidate's `candidateRemoteId`, not a Room row id — a re-sync is
 * free to hand a row id to a different row, and the memory outlives re-syncs. So a candidate is resolved
 * back through `ChannelDao.findByRemote(sourceId, remoteId)`, the same stable-key lookup Backup & Restore
 * resolves through, and the row that comes back is the one the substitute tune is actually started with:
 * its URL, its headers, its engine preference, its pin.
 *
 * A candidate whose provider gave no id (`candidateRemoteId` null or blank — hand-made M3U rows routinely
 * have none) cannot be resolved to a row at all, and is skipped. Phase 1 deliberately keeps such rows in
 * the *table* (they are real relationships), so the skip belongs here, where a row is needed, and not in
 * the pass that remembers them.
 *
 * ### The memory is only as full as a discovery pass has made it
 * Nothing here runs discovery: this answers from what phase 3 has already remembered. A channel no pass
 * has ever looked at therefore has no candidates, and the walk ends exactly where it did before any of
 * this existed — which is also what the player gets from an app that supplies no
 * [LiveTuneController.ProviderFallback] at all.
 */
class SmartProviderFallback(
    private val candidates: SmartProviderCandidateService,
    /** `ChannelDao.findByRemote(sourceId, remoteId)` — the candidate's own row on its own playlist. */
    private val channelByRemote: suspend (sourceId: Long, remoteId: String) -> ChannelEntity?,
    /**
     * Whether [ChannelEntity] is a row the app would show the active profile — in production,
     * `LiveViewModel.isVisibleToActiveProfile`. Injected rather than reimplemented so the failover can
     * never surface a channel the rest of the app hides.
     */
    private val isVisible: suspend (ChannelEntity) -> Boolean,
    /** Where the two verdicts are written from: they arrive on the player's thread, unawaited. */
    private val scope: CoroutineScope,
    /** Epoch milliseconds for the stamps, injectable so a test does not race a real clock. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LiveTuneController.ProviderFallback {

    /**
     * The next playlist that may carry [anchor], in the memory's own order — most recently proven first,
     * `sourceId` ascending for the ties (`SmartProviderDao.forAnchor`) — or null when there is nothing left
     * for the player to try.
     *
     * Never re-sorted here: the persisted order is the judgement, and re-ranking it in the app would make
     * what the table records and what the player does two different things.
     *
     * The skips are all "cannot use this one right now", never "this relationship is wrong": a playlist
     * already spent in this walk, a candidate whose provider gave no id to resolve, a row the playlist no
     * longer has, and a row the app would not show the active profile — wrong profile, its Live section
     * switched off, a hidden channel or category, an adult group the profile blocks. A substitute that got
     * past that check would put a channel on screen that Browse, search and History all hide.
     */
    override suspend fun nextCandidate(
        anchor: ChannelEntity,
        triedSourceIds: Set<Long>,
    ): ChannelEntity? {
        for (candidate in candidates.candidatesFor(anchor)) {
            if (candidate.sourceId in triedSourceIds) continue
            val remoteId = candidate.candidateRemoteId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val channel = channelByRemote(candidate.sourceId, remoteId) ?: continue
            if (!isVisible(channel)) continue
            return channel
        }
        return null
    }

    /** A picture: the one thing that ends a walk, and the strongest thing this table can learn. */
    override fun onOpened(anchor: ChannelEntity, channel: ChannelEntity) {
        val at = nowMs()
        scope.launch { candidates.recordSuccess(anchor, channel.sourceId, at) }
    }

    /**
     * No picture: remembered against the playlist only when the player's own text names something the
     * *provider* did — an HTTP verdict, a one-session refusal, a certificate it could not verify. Everything
     * else (a decoder this device cannot start, a format its mpv refuses, "no picture within 30 s") says
     * nothing the app should hold against a playlist, and is dropped silently.
     *
     * A stamp is only ever written on a row a discovery already wrote, and the write is dispatched because
     * the hook is not suspending: a failing tune must not wait on the database, and a lost stamp costs the
     * next attempt some ordering, never a picture.
     */
    override fun onFailed(anchor: ChannelEntity, channel: ChannelEntity, detail: String) {
        val reason = PlayerErrors.reasonFor(detail)
            ?.let { player ->
                SmartProviderCandidateService.ProviderFailureReason.entries.firstOrNull { it.player == player }
            }
            ?: return
        val at = nowMs()
        scope.launch { candidates.recordFailure(anchor, channel.sourceId, at, reason) }
    }
}
