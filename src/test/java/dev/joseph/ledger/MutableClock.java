package dev.joseph.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A Clock a test can move forward. It follows the real time plus an offset, so code that measures short waits still
 * behaves normally, and {@link #advance} jumps ahead (for example 25 hours, to make an idempotency key expire)
 * without sleeping. {@link #reset} puts it back to real time.
 */
final class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
