package tv.own.owntv.smart

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sync.SyncActivityTracker

/**
 * The trigger's contract, which is one sentence — *a pass runs when the catalogue has settled quiet,
 * once, and stops the moment it stops being quiet* — and the awkward cases around it.
 *
 * Almost every case here is a case that would be a **wrong pass**: a pass at process start because the
 * tracker's initial value is `emptyMap()`, a pass per playlist because three syncs ran, a pass while a
 * fourth is still running, a pass on top of a tune, two passes at once because two settlings were
 * witnessed. Some are the opposite — a pass that should happen and must not be lost (a sync already
 * running when the observer attaches; a pass that yields and must still be reachable afterwards) —
 * because a trigger that never fires passes the first kind of test and none of the second.
 *
 * The last section is the same shape from the other side: a pass that *fails* is still a trigger that
 * works — the observer has to survive it, and the next sync has to bring the next pass.
 *
 * The window is a real 10 ms rather than 0 and every expectation is an await, not an assertion made on
 * the same thread as the emission. This project has no coroutines-test dispatcher (see the test
 * dependencies in `app/build.gradle.kts`), so treating "settleMs = 0" as "everything is synchronous"
 * would be a bet on the internals of `debounce` — and the window is part of what is being tested.
 * Nothing here needs a database, a profile store, a player or a screen: the seams are lambdas, the
 * tracker is a `MutableStateFlow` the test writes, and the shape is `SmartProviderCandidateServiceTest`'s.
 */
class SmartProviderDiscoveryTriggerTest {

    private fun channel(id: Long, sourceId: Long = 1) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = "Ch $id",
        streamUrl = "http://example.invalid/$id.ts",
        remoteId = "$id",
    )

    private val threeAnchors = listOf(channel(1), channel(2), channel(3))

    /**
     * The trigger, a tracker this test drives directly, and the seams that lead to a discovery call.
     *
     * The counters are what make the assertions about *passes* rather than about calls: one pass reads
     * the profile once and the anchors once, so `profileReads`/`anchorReads` tell a single pass apart
     * from three. The collections are concurrent because a pass runs on whichever thread the window
     * resumes on, and `gate` parks one mid-anchor so the checks between anchors can be observed.
     */
    private class Harness(
        anchors: List<ChannelEntity> = emptyList(),
        sourceIds: List<Long> = listOf(1L, 2L),
        profileId: Long? = 1L,
        settleMs: Long = 10L,
        gate: CompletableDeferred<Unit>? = null,
    ) {
        /**
         * Exceptions nothing caught. The observer's real scope is the app's and has no handler behind
         * it, so anything landing here would be a crash on a device; recording it is what makes "the
         * failure did not escape" an assertion instead of an inference from a trigger still working.
         */
        val uncaught = CopyOnWriteArrayList<Throwable>()

        val scope = CoroutineScope(
            Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> uncaught += error },
        )
        val syncActivity = MutableStateFlow<Map<Long, SyncActivityTracker.ActiveSync>>(emptyMap())
        val discovered = CopyOnWriteArrayList<ChannelEntity>()

        val profileReads = AtomicInteger(0)
        val anchorReads = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        /** Discovery calls made, including the one a [failureAt] throw skipped. */
        val attempts = AtomicInteger(0)

        @Volatile var playback = false
        @Volatile var anchorLimit = -1
        @Volatile var queriedProfileId = -1L
        @Volatile var queriedSourceIds: List<Long> = emptyList()

        /** Runs after the Nth discovery of a pass, from the pass's own thread. */
        @Volatile var afterDiscovery: ((Int) -> Unit)? = null

        /** Throw [failure] from the Nth discovery call instead of discovering anything. */
        @Volatile var failureAt: Int? = null
        @Volatile var failure: Throwable = IllegalStateException("discovery failed")

        val trigger = SmartProviderDiscoveryTrigger(
            syncActivity = syncActivity,
            playbackActive = { playback },
            activeProfileId = {
                profileReads.incrementAndGet()
                profileId
            },
            sourceIdsForProfile = { id ->
                queriedProfileId = id
                sourceIds
            },
            // Returns every anchor it was given, whatever limit it was asked for: the walk's own cap is
            // then the thing under test, rather than the query's.
            recentlyWatchedAnchors = { id, ids, limit ->
                anchorReads.incrementAndGet()
                queriedProfileId = id
                queriedSourceIds = ids
                anchorLimit = limit
                anchors
            },
            discover = { anchor ->
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> if (a > b) a else b }
                try {
                    gate?.await()
                    if (attempts.incrementAndGet() == failureAt) throw failure
                    discovered += anchor
                    afterDiscovery?.invoke(discovered.size)
                } finally {
                    inFlight.decrementAndGet()
                }
            },
            settleMs = settleMs,
        )

        /** One sync starts. Several ids at once is what parallel playlists look like to the tracker. */
        fun startSync(vararg ids: Long) {
            ids.forEach { id ->
                syncActivity.value = syncActivity.value + (id to SyncActivityTracker.ActiveSync(id, "source-$id"))
            }
        }

        fun finishSync(vararg ids: Long) {
            ids.forEach { id -> syncActivity.value = syncActivity.value - id }
        }

        /** Wait for [count] discoveries, and fail rather than hang if they never come. */
        fun awaitDiscovery(count: Int) = runBlocking {
            withTimeout(5_000) { while (discovered.size < count) delay(5) }
        }

        /** Wait for a pass to be parked inside [count] discovery calls. */
        fun awaitInFlight(count: Int) = runBlocking {
            withTimeout(5_000) { while (inFlight.get() < count) delay(5) }
        }

        /** Wait for [count] discovery calls, whether they discovered or threw. */
        fun awaitAttempts(count: Int) = runBlocking {
            withTimeout(5_000) { while (attempts.get() < count) delay(5) }
        }

        /** Long enough that a window which was going to elapse has, so "nothing happened" means it. */
        fun quiet() = runBlocking { delay(120) }

        fun stop() = scope.cancel()
    }

    // ---- when a pass runs ------------------------------------------------------------------------

    @Test
    fun `the initial empty map is not an edge`() {
        val h = Harness(anchors = threeAnchors)

        h.trigger.start(h.scope)
        h.quiet()

        // The StateFlow's initial value is emptyMap(); a process start is not a sync finishing.
        assertTrue(h.discovered.isEmpty())
        assertEquals(0, h.profileReads.get())
        assertEquals(0, h.anchorReads.get())
        h.stop()
    }

    @Test
    fun `a sync that starts and finishes runs exactly one pass`() {
        val h = Harness(anchors = threeAnchors)
        h.trigger.start(h.scope)

        h.startSync(1)
        h.quiet()
        // Non-empty is not an edge on its own: nothing may start while the sync is running.
        assertTrue(h.discovered.isEmpty())

        h.finishSync(1)
        h.awaitDiscovery(threeAnchors.size)
        h.quiet()

        assertEquals(threeAnchors, h.discovered)
        assertEquals(1, h.profileReads.get())
        assertEquals(1, h.anchorReads.get())
        h.stop()
    }

    @Test
    fun `a sync already running when the observer attaches still ends in one pass`() {
        val h = Harness(anchors = threeAnchors)
        // The map is non-empty before anything is listening — the drop(1) case that must not become
        // "no pass ever, until the next sync".
        h.startSync(1)

        h.trigger.start(h.scope)
        h.quiet()
        assertTrue(h.discovered.isEmpty())

        h.finishSync(1)
        h.awaitDiscovery(threeAnchors.size)

        assertEquals(threeAnchors, h.discovered)
        h.stop()
    }

    @Test
    fun `one of two parallel syncs finishing does not trigger`() {
        val h = Harness(anchors = threeAnchors)
        h.trigger.start(h.scope)

        h.startSync(1, 2)
        h.finishSync(1)
        h.quiet()
        assertTrue(h.discovered.isEmpty())

        h.finishSync(2)
        h.awaitDiscovery(threeAnchors.size)

        assertEquals(threeAnchors, h.discovered)
        h.stop()
    }

    @Test
    fun `parallel syncs collapsing into one empty map run one pass, not three`() {
        val h = Harness(anchors = threeAnchors)
        h.trigger.start(h.scope)

        h.startSync(1, 2, 3)
        h.finishSync(1, 2, 3)
        h.awaitDiscovery(threeAnchors.size)
        h.quiet()

        assertEquals(1, h.anchorReads.get())
        assertEquals(threeAnchors, h.discovered)
        h.stop()
    }

    @Test
    fun `a sync arriving inside the settle window defers the pass`() {
        val h = Harness(anchors = listOf(channel(1)), settleMs = 200)
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        // Part-way through the window: this restarts it rather than letting it elapse into a pass.
        runBlocking { delay(80) }
        h.startSync(2)
        // Past where the first window would have ended had it not been restarted.
        runBlocking { delay(220) }
        assertTrue(h.discovered.isEmpty())

        // …and the edge the second sync brings is still honoured.
        h.finishSync(2)
        h.awaitDiscovery(1)
        h.quiet()

        assertEquals(1, h.discovered.size)
        h.stop()
    }

    // ---- when a pass yields ----------------------------------------------------------------------

    @Test
    fun `playback prevents a pass from starting`() {
        val h = Harness(anchors = threeAnchors)
        h.playback = true
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.quiet()

        assertTrue(h.discovered.isEmpty())
        // Not even read: the yield is checked before the profile is resolved, so a tune costs no query.
        assertEquals(0, h.profileReads.get())
        assertEquals(0, h.anchorReads.get())

        // …and the trigger is not simply dead: the same edge with nothing playing does run.
        h.playback = false
        h.startSync(2)
        h.finishSync(2)
        h.awaitDiscovery(threeAnchors.size)

        assertEquals(threeAnchors, h.discovered)
        h.stop()
    }

    @Test
    fun `playback starting between anchors stops the pass`() {
        val h = Harness(anchors = threeAnchors)
        h.afterDiscovery = { count -> if (count == 1) h.playback = true }
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.awaitDiscovery(1)
        h.quiet()

        assertEquals(threeAnchors.take(1), h.discovered)
        h.stop()
    }

    @Test
    fun `a sync starting between anchors stops the pass`() {
        val h = Harness(anchors = threeAnchors)
        h.afterDiscovery = { count -> if (count == 1) h.startSync(9) }
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.awaitDiscovery(1)
        h.quiet()

        assertEquals(threeAnchors.take(1), h.discovered)
        h.stop()
    }

    // ---- what a pass walks -----------------------------------------------------------------------

    @Test
    fun `no active profile means no pass`() {
        val h = Harness(anchors = threeAnchors, profileId = null)
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.quiet()

        assertEquals(1, h.profileReads.get()) // asked, and answered null
        assertEquals(0, h.anchorReads.get())
        assertTrue(h.discovered.isEmpty())
        h.stop()
    }

    @Test
    fun `a profile with no playlists walks nothing`() {
        val h = Harness(anchors = threeAnchors, sourceIds = emptyList())
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.quiet()

        assertEquals(1, h.profileReads.get())
        assertEquals(0, h.anchorReads.get())
        assertTrue(h.discovered.isEmpty())
        h.stop()
    }

    @Test
    fun `the active profile's playlists and budget are what the read is asked for`() {
        val h = Harness(anchors = listOf(channel(7, sourceId = 2)), sourceIds = listOf(2L, 3L))
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.awaitDiscovery(1)

        assertEquals(1L, h.queriedProfileId)
        assertEquals(listOf(2L, 3L), h.queriedSourceIds)
        assertEquals(SmartProviderDiscoveryTrigger.ANCHOR_BUDGET, h.anchorLimit)
        h.stop()
    }

    @Test
    fun `the anchor budget caps the walk as well as the read`() {
        val many = (1L..25L).map { channel(it) }
        val h = Harness(anchors = many)
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.awaitDiscovery(SmartProviderDiscoveryTrigger.ANCHOR_BUDGET)
        h.quiet()

        assertEquals(SmartProviderDiscoveryTrigger.ANCHOR_BUDGET, h.anchorLimit)
        // The seam hands back all 25 whatever it was asked for, so this is the walk's own cap.
        assertEquals(
            many.take(SmartProviderDiscoveryTrigger.ANCHOR_BUDGET),
            h.discovered,
        )
        h.stop()
    }

    // ---- one pass at a time, and only one owner ------------------------------------------------

    @Test
    fun `anchors are walked one at a time, in order`() {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(anchors = threeAnchors, gate = gate)
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        // Parked inside the first anchor: exactly one call is open and no second one has been made.
        h.awaitInFlight(1)
        assertEquals(1, h.inFlight.get())
        assertTrue(h.discovered.isEmpty())

        gate.complete(Unit)
        h.awaitDiscovery(threeAnchors.size)

        assertEquals(threeAnchors, h.discovered)
        assertEquals(1, h.maxInFlight.get())
        h.stop()
    }

    @Test
    fun `a settling during a pass is never answered by a concurrent pass`() {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(anchors = threeAnchors, gate = gate)
        h.trigger.start(h.scope)

        h.startSync(1)
        h.finishSync(1)
        h.awaitInFlight(1)

        // A whole second sync cycle while the pass is parked, and long enough for its settling to have
        // elapsed: the second pass is answered *after* the first, never inside it or alongside it.
        h.startSync(2)
        h.finishSync(2)
        h.quiet()
        assertEquals(1, h.inFlight.get())
        assertTrue(h.discovered.isEmpty())

        gate.complete(Unit)
        h.awaitDiscovery(threeAnchors.size * 2)
        h.quiet()

        assertEquals(threeAnchors + threeAnchors, h.discovered)
        assertEquals(2, h.anchorReads.get())
        assertEquals(1, h.maxInFlight.get())
        h.stop()
    }

    @Test
    fun `starting twice adds no second observer`() {
        val h = Harness(anchors = threeAnchors)
        val secondStartup = CoroutineScope(Dispatchers.Unconfined)
        h.trigger.start(h.scope)
        h.trigger.start(secondStartup)

        h.startSync(1)
        h.finishSync(1)
        h.awaitDiscovery(threeAnchors.size)
        h.quiet()

        assertEquals(1, h.anchorReads.get())
        assertEquals(threeAnchors, h.discovered)
        h.stop()
        secondStartup.cancel()
    }

    @Test
    fun `its owner is a scope, not a screen`() {
        // A structural check, and the only kind available for "does not depend on Activity or ViewModel
        // lifecycle": nothing this class is built from, or started with, may be an Android lifecycle type.
        // If that ever stopped being true, a pass would be tied to a screen that is usually not on screen.
        SmartProviderDiscoveryTrigger::class.java.declaredConstructors.forEach { constructor ->
            constructor.parameterTypes.forEach { type ->
                val name = type.name
                assertFalse(name, name.startsWith("android."))
                assertFalse(name, name.contains("ViewModel"))
                assertFalse(name, name.endsWith("Activity"))
            }
        }
        // The one thing start() asks for is the lifetime the observer should have.
        val start = SmartProviderDiscoveryTrigger::class.java
            .getDeclaredMethod("start", CoroutineScope::class.java)
        assertEquals(CoroutineScope::class.java, start.parameterTypes.single())
    }

    // ---- one failure, contained -------------------------------------------------------------------

    @Test
    fun `a failed pass is dropped, and the next settling is an ordinary pass`() {
        val h = Harness(anchors = threeAnchors)
        // The first anchor of the first pass fails; nothing else is asked to.
        h.failureAt = 1
        h.trigger.start(h.scope)

        h.startSync(1)
        h.quiet()
        h.finishSync(1)
        h.awaitAttempts(1)
        h.quiet()

        // Nothing escaped onto the scope the observer runs on — on a device that scope is the app's and
        // would be a crash — and the pass was not retried: one read of the anchors, one attempt, and no
        // second one behind it.
        assertTrue(h.uncaught.isEmpty())
        assertTrue(h.discovered.isEmpty())
        assertEquals(1, h.anchorReads.get())
        assertEquals(1, h.attempts.get())

        // The observer is still observing: a later sync's settling is an ordinary, complete pass.
        h.startSync(2)
        h.quiet()
        h.finishSync(2)
        h.awaitDiscovery(threeAnchors.size)
        h.quiet()

        assertTrue(h.uncaught.isEmpty())
        assertEquals(threeAnchors, h.discovered)
        assertEquals(2, h.anchorReads.get())
        h.stop()
    }

    @Test
    fun `a cancelled pass is not swallowed`() {
        val h = Harness(anchors = threeAnchors)
        h.failureAt = 1
        h.failure = CancellationException("the observer's scope is going away")
        h.trigger.start(h.scope)

        h.startSync(1)
        h.quiet()
        h.finishSync(1)
        h.awaitAttempts(1)
        h.quiet()

        // A CancellationException is the scope saying stop, not work saying it failed: it is passed
        // through, and a coroutine that ends cancelled reports nothing as uncaught.
        assertTrue(h.uncaught.isEmpty())
        assertTrue(h.discovered.isEmpty())

        // A later settling is therefore *not* answered — which is the whole difference. Had the failure
        // been caught as a failure instead, this second cycle would have run a second, complete pass.
        h.startSync(2)
        h.quiet()
        h.finishSync(2)
        h.quiet()

        assertEquals(1, h.attempts.get())
        assertEquals(1, h.anchorReads.get())
        h.stop()
    }
}
