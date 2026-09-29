package dev.joseph.ledger.domain;

import java.util.UUID;

/**
 * The fixed ids of the seeded SYSTEM accounts (migration V4). They are constants because the rows are inserted by a
 * migration, not created at runtime, and a test checks these values against the database.
 */
public final class SystemAccountIds {

    /** Counter-side of every deposit: money entering the ledger from outside. */
    public static final UUID EXTERNAL_FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Reserved for withdrawals (a deferred requirement). Nothing posts to it yet. */
    public static final UUID EXTERNAL_PAYOUTS = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** True for the seeded SYSTEM accounts, which have no cached balance and are never locked. */
    public static boolean isSystem(UUID accountId) {
        return EXTERNAL_FUNDING.equals(accountId) || EXTERNAL_PAYOUTS.equals(accountId);
    }

    private SystemAccountIds() {}
}
