@file:OptIn(FlowPreview::class) // debounce — the settle window in observe()

package tv.own.owntv.smart

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tv.own.owntv.core.CorePerf
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sync.SyncActivityTracker

/**
 * Smart Provider Engine — **phase 3's trigger**: the one thing in the app that decides *when*
 * [SmartProviderCandidateService.discover] runs, and the only caller it has.
 *
 * Phase 3 knows how to answer "where else can this channel be watched" and remembers the answer in
 * core's candidate table; this class asks it and nothing else does. There is no scheduler, no retry, no
 * probe and no failover decision here — those are phase 4's, on the player's own path.
 *
 * ### The trigger is the catalogue going quiet
 * [SyncActivityTracker.active] is a map of what is syncing right now, keyed by source id. A pass runs
 * when that map has been **empty for the whole settle window**, having not been empty before. It is the
 * right signal because of what it already is rather than anything done to it here: several playlists
 * syncing at once are several entries, and they collapse into *one* transition from non-empty to empty.
 * The coalescing is intrinsic to the map, not logic bolted on top of it, and the tracker is already a
 * core singleton that `CatalogBackfillWorker` reads to decide whether to yield.
 *
 * ### The first emission is not an edge
 * `active` is a `StateFlow` whose initial value is `emptyMap()`. A collector that read "empty" as the
 * trigger would start a pass at every process start and again on every re-subscription. So [observe]
 * drops the first value and considers only *changes*: a StateFlow always emits where it currently is
 * before it emits anything else, which makes the one dropped value exactly the one describing a state
 * rather than a transition. That is the whole latch — no `sawSyncActivity` boolean — and a process that
 * starts *while* a sync is already running is still handled, because that sync's completion is the next
 * change.
 *
 * ### The settle window, and why it sits on the raw changes
 * `SyncManager.sync` reports a sync finished before `CatalogSyncWorker` has run the work that follows
 * it: the import finalizer, the content-index schedule, EPG guide-gap work, the launcher and trending
 * refreshes. The window exists to keep discovery off that tail. It is a `debounce` over the tracker's
 * raw emptiness changes, so any further change restarts it — a sync that begins inside the window is
 * activity, and the window is about the map having been empty all the way through. The "is it empty"
 * filter therefore sits *after* the debounce, not before: filtering first would let a stretch of
 * emptiness emit while a sync was mid-flight.
 *
 * [PROVISIONAL_SETTLE_MS] is a placeholder, not a measurement — the tail it stands in front of has
 * never been timed on a device, and this class's [CorePerf] lines are what will time it.
 *
 * ### What a pass does
 * The active profile, then that profile's playlists, then at most [ANCHOR_BUDGET] of its most recently
 * watched live channels — the ones a household has actually used, and so the only ones worth spending
 * CPU on. They are walked strictly one at a time. Before every anchor the pass yields if a sync has
 * started or if something is playing: discovery is the lowest-priority work in the app, and one anchor
 * is the smallest unit it can abandon.
 *
 * Nothing about the pass itself is persisted. Process death discards it and the next sync brings the
 * next one; the anchors are re-read every time, so there is no progress worth resuming. Candidate
 * stamps are not this class's business and are left exactly as discovery wrote them, and there is no
 * pruning here — deliberately, because a pass may only add to what is remembered.
 *
 * ### Settlings are answered one at a time, never two at once
 * The collector is a single sequential coroutine, and [running] states the invariant rather than leaving
 * it to be inferred from how the operators happen to compose: two trigger events can never produce two
 * passes *at the same time*. A settling that arrives while a pass is already running is not folded into
 * it — a sync that ran mid-pass may have changed the very pools that pass read — so it is answered by
 * one more pass afterwards, bounded like every other by the budget and the yield checks.
 *
 * ### A failed pass is dropped, never retried, and never takes the observer with it
 * The observer's scope is the process's, and nothing is behind it to catch a throwable: one escaping
 * [observe] would be an uncaught exception, and the pass would be lost with every pass after it. So a
 * failure inside a pass is contained at the one place it can be — the collector — logged through
 * [CorePerf] and dropped. The failed pass is not retried; the next settling after a later sync is an
 * ordinary pass, because nothing about the failure is remembered. Cancellation is not a failure and is
 * not contained: [start]'s scope is the only thing that ends the observer.
 *
 * ### Its lifetime is the process's
 * [start] takes a [CoroutineScope] and nothing else: no Activity, no ViewModel, no Context. Production
 * passes `OwnTVApp.appScope`, so the observer outlives every screen — which is the point, because the
 * sync it waits for usually finishes while the user is somewhere else. That scope also decides the
 * thread; production's is IO-backed, so no dispatcher is chosen here.
 */
class SmartProviderDiscoveryTrigger(
    /**
     * `SyncActivityTracker.active` — what is syncing, keyed by source id. Both the trigger and the
     * yield condition, so it arrives as the one member this class actually reads rather than as the
     * tracker, which would let a pass report a sync it never started.
     */
    private val syncActivity: StateFlow<Map<Long, SyncActivityTracker.ActiveSync>>,
    /**
     * Whether anything is playing — in production
     * `watchSession.watching.value.isNotEmpty() || openStreams.claims.value.isNotEmpty()`, the pair
     * `CatalogBackfillWorker` and `ConnectionMeasurementWorker` already yield to. Injected as a
     * predicate for the same reason those take the objects: a unit test cannot stand the two core
     * classes up, and a lambda is what every collaborator here is.
     */
    private val playbackActive: () -> Boolean,
    /**
     * The active profile, or null when the device has none —
     * `profileDao.resolveExistingProfileId(settings.activeProfileId.first())`. Null is the whole answer
     * for that case: discovery is profile-scoped work, and there is nothing to scope it to.
     */
    private val activeProfileId: suspend () -> Long?,
    /** `SourceDao.sourceIdsForProfile(profileId)` — the playlists the pass is allowed to read. */
    private val sourceIdsForProfile: suspend (profileId: Long) -> List<Long>,
    /**
     * The profile's recently watched live channels, most recent first — in production
     * `ChannelDao.recentlyWatchedWithTimestampFiltered(profileId, sourceIds, limit).first()`, mapped to
     * its channels. That query applies the source filter *before* the LIMIT and orders by `watchedAt`
     * DESC, which is the order this pass walks; the filter having to come first is the reason the
     * profile's playlists are resolved in this class rather than left to the caller.
     */
    private val recentlyWatchedAnchors: suspend (
        profileId: Long,
        sourceIds: List<Long>,
        limit: Int,
    ) -> List<ChannelEntity>,
    /**
     * One anchor's discovery — in production `SmartProviderCandidateService.discover(anchor)`. Taken as
     * a lambda because the pass needs the *call* and nothing else: what a discovery found is phase 4's
     * business, and a test can then count and order calls without a database.
     */
    private val discover: suspend (ChannelEntity) -> Unit,
    /** See [PROVISIONAL_SETTLE_MS]. A parameter so a test can shorten it, never to be configured. */
    private val settleMs: Long = PROVISIONAL_SETTLE_MS,
    /** See [ANCHOR_BUDGET]. A parameter so a test can shrink it, never to be configured. */
    private val anchorBudget: Int = ANCHOR_BUDGET,
) {

    /** Guards [start]: one observer per process, however many times startup runs. */
    private val started = AtomicBoolean(false)

    /** Guards [runPass]: a pass is never entered twice at once, whatever else calls it later. */
    private val running = AtomicBoolean(false)

    /**
     * Attach the observer to [scope]. Idempotent: a second call — a recreated screen, a second startup
     * path — is a no-op rather than a second observer that would double every pass.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { observe() }
    }

    /**
     * The whole trigger: wait for the tracker to settle empty, then run one pass. Runs on whatever
     * dispatcher [start]'s scope supplies and returns only when that scope is cancelled.
     */
    private suspend fun observe() {
        syncActivity
            .map { it.isEmpty() }
            .distinctUntilChanged()
            // The first value describes the state the collector attached to; only a change from it is
            // an edge. See the class note.
            .drop(1)
            .map { empty -> Quiet(empty, SystemClock.elapsedRealtime()) }
            // Every witnessed edge, which is what the settle window is measured against. The tracker is
            // silent — nothing else in the app logs when a sync starts or finishes — so without this line
            // a trace could not tell a second sync appearing *inside* a window, which restarts it, from a
            // window that was simply too short: both look like the same stretch of nothing at all. The
            // lambda is the only work this adds, and on a user's device [CorePerf] never invokes it.
            .onEach { change ->
                CorePerf.log { "smart-discovery witnessed empty=${change.empty} syncing=${syncActivity.value.keys}" }
            }
            // Any change restarts the window — see the class note on why this precedes the filter.
            .debounce(settleMs)
            .filter { it.empty }
            .collect { quiet ->
                // Nothing is watching but the process itself: the observer runs on a scope with no
                // handler behind it, so a throwable that escapes here is an uncaught exception — it
                // would risk the app *and* end the collector, leaving discovery off until the next
                // process start. A settled catalogue is a weak enough signal that one failed pass is
                // worth neither: it is logged and dropped, and the next settling after a later sync is
                // an ordinary pass. No retry, no backoff, no counter. Cancellation is not a failure —
                // the scope is the only thing that ends the observer — so it is passed through.
                try {
                    runPass(quiet.at)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Throwable) {
                    CorePerf.log { "smart-discovery failed error=${failed.javaClass.simpleName}" }
                }
            }
    }

    /**
     * One bounded pass: resolve the profile, read its anchors, walk them one at a time, and stop the
     * moment watching or syncing starts. [quietAt] is when the tracker was last seen empty — the trace
     * reports the distance, which is the number the settle window is currently guessing at.
     */
    private suspend fun runPass(quietAt: Long) {
        if (!running.compareAndSet(false, true)) return
        try {
            // Before anything is read: a tune or a sync that began while the window was elapsing is
            // reason enough not to open a single cursor.
            val beforeStart = yieldReason()
            if (beforeStart != null) {
                CorePerf.log { "smart-discovery skip reason=$beforeStart" }
                return
            }

            val profileId = activeProfileId()
            if (profileId == null) {
                CorePerf.log { "smart-discovery skip reason=no-profile" }
                return
            }
            val sourceIds = sourceIdsForProfile(profileId)
            if (sourceIds.isEmpty()) {
                CorePerf.log { "smart-discovery skip reason=no-sources profile=$profileId" }
                return
            }
            // The DAO's LIMIT bounds the read; this bounds the walk, so the budget holds whatever a
            // seam hands back.
            val anchors = recentlyWatchedAnchors(profileId, sourceIds, anchorBudget).take(anchorBudget)
            if (anchors.isEmpty()) {
                CorePerf.log { "smart-discovery skip reason=no-anchors profile=$profileId sources=${sourceIds.size}" }
                return
            }

            val startedAt = SystemClock.elapsedRealtime()
            CorePerf.log {
                "smart-discovery start profile=$profileId anchors=${anchors.size} sources=${sourceIds.size} " +
                    "settleMs=$settleMs quietMs=${startedAt - quietAt}"
            }

            var done = 0
            var yielded: Outcome? = null
            for (anchor in anchors) {
                // Between anchors, not only before the first: a tune that starts while the pass is
                // walking it is still a reason to stop walking it.
                val reason = yieldReason()
                if (reason != null) {
                    yielded = reason
                    break
                }
                val anchorStartedAt = SystemClock.elapsedRealtime()
                discover(anchor)
                done++
                CorePerf.log {
                    "smart-discovery anchor i=$done/${anchors.size} sourceId=${anchor.sourceId} " +
                        "remoteId=${anchor.remoteId} ms=${SystemClock.elapsedRealtime() - anchorStartedAt}"
                }
            }

            val outcome = yielded ?: Outcome.COMPLETED
            CorePerf.log {
                "smart-discovery end profile=$profileId selected=${anchors.size} done=$done " +
                    "outcome=$outcome totalMs=${SystemClock.elapsedRealtime() - startedAt}"
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * Why a pass may not run right now, or null when it may. Syncing is asked first because it is the
     * cheaper read, and because it is the state the trigger itself is built on.
     */
    private fun yieldReason(): Outcome? = when {
        syncActivity.value.isNotEmpty() -> Outcome.YIELDED_SYNC
        playbackActive() -> Outcome.YIELDED_PLAYBACK
        else -> null
    }

    /**
     * One witnessed change to the tracker's emptiness, carried through the settle window: what changed,
     * and when it was seen — which is the timestamp the trace reports the window against.
     */
    private data class Quiet(val empty: Boolean, val at: Long)

    /** How a pass ended, for the trace. */
    private enum class Outcome {
        /** Every selected anchor was walked. */
        COMPLETED,

        /** The walk stopped because a sync started. */
        YIELDED_SYNC,

        /** The walk stopped because something was playing. */
        YIELDED_PLAYBACK,
    }

    companion object {
        /**
         * The most anchors one pass may walk. A pass reads the whole candidate pool *per anchor*, and the
         * one comparable scan this repository has measured (the core EPG matcher) was ~3.4M scorings, or
         * roughly half an hour of CPU on a 2020 Android TV. So this is a budget rather than a target:
         * twenty recently watched channels are the ones a household actually uses, and the only ones
         * worth that price.
         *
         * Not being redesigned yet — the first `smart-discovery` trace from a real device is what should
         * move it.
         */
        const val ANCHOR_BUDGET = 20

        /**
         * **PROVISIONAL — not a measured number, and not to be frozen.** How long the catalogue must have
         * been quiet before a pass starts.
         *
         * `SyncManager.sync` reports finished before `CatalogSyncWorker` has run the import finalizer, the
         * content-index schedule, EPG guide-gap work, the launcher and trending refreshes. This window is
         * what stands in front of that tail. The tail has never been timed on a device, so the value is a
         * placeholder chosen to be obviously longer than a guess deserves — the same discipline
         * `EpgViewModel.GUIDE_DATA_SETTLE_MS` was measured to, and `quietMs=` in this class's [CorePerf]
         * trace is the number that will replace it.
         */
        const val PROVISIONAL_SETTLE_MS = 10_000L
    }
}
