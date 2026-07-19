package stock.batch.service.batch.signal.biz;

/**
 * Indicates that a signal is no longer owned by the current claim token.
 *
 * <p>The caller must not perform a terminal update with the stale token. Another scheduler may
 * already have reclaimed the signal after its lease expired.</p>
 */
final class BatchJobSignalClaimLostException extends IllegalStateException {

    BatchJobSignalClaimLostException(long signalId) {
        super("Batch signal claim was lost: id=" + signalId);
    }
}
