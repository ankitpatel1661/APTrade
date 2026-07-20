import XCTest
@testable import APTradeApplication

/// A manually-releasable async gate: `wait()` suspends until `release()` is called
/// (possibly from a different Task), giving tests a deterministic way to force two
/// concurrently-launched `Task`s to actually overlap at a specific point rather than
/// relying on real-world scheduling luck.
private actor Gate {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

/// Append-only, actor-guarded event log shared across concurrently-launched Tasks.
private actor EventLog {
    private(set) var events: [String] = []
    func append(_ event: String) { events.append(event) }
}

final class TradeSerializerTests: XCTestCase {

    // MARK: (c-adjacent) Bodies that suspend mid-execution never interleave.

    func test_run_bodiesThatSuspendMidExecution_neverInterleave() async throws {
        let serializer = TradeSerializer()
        let log = EventLog()
        let firstGate = Gate()
        let secondStarted = Gate()

        // First body: logs its start, blocks on `firstGate`, then logs its end.
        let firstTask = Task {
            await serializer.run {
                await log.append("first-start")
                await firstGate.wait()
                await log.append("first-end")
            }
        }

        // Give `firstTask` a real chance to actually enter its body and reach the gate
        // before enqueuing the second call — otherwise "second never starts before
        // first" would be true trivially because second hasn't even been launched yet.
        while await log.events.isEmpty { await Task.yield() }

        // Second body: enqueued while first is still blocked on `firstGate`. If the
        // serializer let bodies interleave, this would log "second-start" immediately,
        // BEFORE `firstGate` is ever released.
        let secondTask = Task {
            await serializer.run {
                await log.append("second-start")
                await secondStarted.release()
                await log.append("second-end")
            }
        }

        // A generous but bounded wait: second must NOT have started yet, since first is
        // still parked on `firstGate`.
        try await Task.sleep(for: .milliseconds(150))
        let midEvents = await log.events
        XCTAssertEqual(midEvents, ["first-start"], "second body must not start while first is still in flight")

        await firstGate.release()
        _ = await (firstTask.value, secondTask.value)

        let finalEvents = await log.events
        XCTAssertEqual(finalEvents, ["first-start", "first-end", "second-start", "second-end"],
                       "bodies must run strictly FIFO, never interleaved")
    }

    // MARK: (c) Cancellation of a queued waiter does not deadlock the chain.

    func test_run_cancellingAQueuedWaiter_doesNotDeadlockLaterWaiters() async throws {
        let serializer = TradeSerializer()
        let log = EventLog()
        let gate = Gate()

        // Slot 1: holds the lock until `gate` is released.
        let slot1 = Task {
            await serializer.run {
                await log.append("slot1-start")
                await gate.wait()
                await log.append("slot1-end")
            }
        }
        while await log.events.isEmpty { await Task.yield() }

        // Slot 2: enqueued behind slot 1, then its OWN wrapping Task is cancelled while
        // still queued (never got a chance to run its body).
        let slot2 = Task {
            await serializer.run {
                await log.append("slot2-ran")
            }
        }
        // A bounded real-time gap: `Task { }` creation order does not itself guarantee
        // enqueue order into the serializer (two freshly-spawned Tasks can race to reach
        // their first `await` on different threads), so this gives slot 2 a generous
        // window to have actually entered the serializer's FIFO queue before slot 3 is
        // created — otherwise this test's own ordering assumption (not the
        // serializer's actual guarantee) would be what's racy.
        try await Task.sleep(for: .milliseconds(50))

        // Slot 3: enqueued behind slot 2. Must still complete despite slot 2's caller
        // cancelling — proves a cancelled queued waiter doesn't wedge the FIFO chain.
        let slot3 = Task {
            await serializer.run {
                await log.append("slot3-ran")
            }
        }

        slot2.cancel()
        await gate.release()
        _ = await slot1.value
        _ = await slot2.value
        _ = await slot3.value

        let events = await log.events
        XCTAssertTrue(events.contains("slot3-ran"), "a later queued body must still run after an earlier waiter is cancelled")
        XCTAssertEqual(events.first, "slot1-start")
        XCTAssertEqual(events.last, "slot3-ran", "slot 3 must run after slot 1 releases (FIFO preserved past the cancellation)")
    }

    // MARK: Errors from `body` propagate, and still release the lock for the next caller.

    func test_run_bodyThrows_propagatesError_andReleasesLockForNextCaller() async throws {
        struct Boom: Error, Equatable {}
        let serializer = TradeSerializer()

        do {
            _ = try await serializer.run { () throws -> Int in throw Boom() }
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(error as? Boom, Boom())
        }

        // The lock must have been released even though the body threw — a second call
        // must be able to proceed (and complete) rather than hang forever.
        let value = await serializer.run { 42 }
        XCTAssertEqual(value, 42)
    }
}
