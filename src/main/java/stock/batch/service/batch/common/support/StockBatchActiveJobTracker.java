package stock.batch.service.batch.common.support;

import java.util.concurrent.TimeUnit;

final class StockBatchActiveJobTracker {

    private final Object monitor = new Object();

    private boolean shuttingDown;
    private int activeJobCount;

    boolean tryEnter() {
        synchronized (monitor) {
            if (shuttingDown) {
                return false;
            }
            activeJobCount++;
            return true;
        }
    }

    void leave() {
        synchronized (monitor) {
            activeJobCount--;
            if (activeJobCount == 0) {
                monitor.notifyAll();
            }
        }
    }

    void markShuttingDown() {
        synchronized (monitor) {
            shuttingDown = true;
        }
    }

    WaitResult waitForActiveJobsToComplete(long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        synchronized (monitor) {
            while (activeJobCount > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return WaitResult.timedOut(activeJobCount);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return WaitResult.interrupted(activeJobCount);
                }
            }
            return WaitResult.completed();
        }
    }

    record WaitResult(Status status, int activeJobCount) {

        static WaitResult completed() {
            return new WaitResult(Status.COMPLETED, 0);
        }

        static WaitResult timedOut(int activeJobCount) {
            return new WaitResult(Status.TIMED_OUT, activeJobCount);
        }

        static WaitResult interrupted(int activeJobCount) {
            return new WaitResult(Status.INTERRUPTED, activeJobCount);
        }

        enum Status {
            COMPLETED,
            TIMED_OUT,
            INTERRUPTED
        }
    }
}
