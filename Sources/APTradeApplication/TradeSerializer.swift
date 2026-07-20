import Foundation

/// Serializes every pie/portfolio mutation so no two mutating use cases ever interleave
/// their load-modify-save sequences.
///
/// **Why actor isolation alone is not enough:** a naive `actor` whose methods do the
/// mutation directly still allows two calls to interleave, because Swift actors are
/// *reentrant* — an actor-isolated method that suspends (e.g. `await`s a network quote)
/// lets a second call into the same actor run concurrently up to ITS first suspension
/// point. Every mutating pie use case awaits a quote, a history fetch, or another store
/// read partway through its load-modify-save sequence, so plain actor isolation would
/// let two contributions each load a stale snapshot, compute against it, and save —
/// with the second save silently clobbering the first (a classic lost-update race).
///
/// **The fix:** `run` builds a strict FIFO mutex instead of relying on reentrancy-safe
/// isolation. Only one body ever executes at a time, for its ENTIRE duration — including
/// every `await` inside it — regardless of what it suspends on. A second `run` call,
/// even one that never awaits, will not start its body until the first body has fully
/// returned or thrown.
///
/// **Guarantee:** bodies passed to `run` execute one at a time, strictly in the order
/// `run` was called (FIFO), never interleaved and never reordered.
///
/// **Cancellation:** a queued waiter that is cancelled (its own `Task` cancelled while
/// still waiting for its turn) does not break the chain — the queue does not depend on
/// the waiter's own cancellation state to make progress, so every other queued body
/// still runs in order and the chain can never deadlock on a cancelled waiter.
public actor TradeSerializer {
    public init() {}

    /// `true` while some body is currently executing (or a queued body is about to be).
    private var isBusy = false
    /// FIFO queue of callers waiting for their turn. `withCheckedContinuation`'s
    /// operation closure runs synchronously (before the caller's `acquire()` call
    /// actually suspends), so appending here can never race with `release()` handing
    /// the turn to the next waiter — there is no window where a continuation could be
    /// resumed before it's recorded.
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Runs `body` after every previously enqueued body has finished (success or
    /// failure), and before any body enqueued after this call starts — true mutual
    /// exclusion, not just actor-isolation's best-effort ordering.
    public func run<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        await acquire()
        do {
            let result = try await body()
            release()
            return result
        } catch {
            release()
            throw error
        }
    }

    /// Claims the lock immediately if free; otherwise enqueues and suspends until
    /// `release()` hands this waiter its turn.
    private func acquire() async {
        guard isBusy else {
            isBusy = true
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    /// Hands the lock to the next queued waiter (resuming its `acquire()` call), or
    /// marks the lock free if the queue is empty. Non-async and synchronous by design —
    /// this is exactly the call `run` makes right after `body` finishes, whether it
    /// returned or threw.
    private func release() {
        guard !waiters.isEmpty else {
            isBusy = false
            return
        }
        let next = waiters.removeFirst()
        next.resume()
    }
}
