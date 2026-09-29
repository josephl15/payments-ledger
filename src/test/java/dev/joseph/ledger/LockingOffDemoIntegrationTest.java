package dev.joseph.ledger;

import org.junit.jupiter.api.Disabled;
import org.springframework.context.annotation.Import;

/**
 * The "locking off" demonstration: the concurrency showcase re-run with the naive, non-locking lock service that
 * Phase 3 shipped, recreated here in the TEST source tree ({@link TestLockServices.NaiveAccountLockService}). The
 * shipped application has no flag or property to switch locking off; only this import does, and only in this class.
 *
 * <p>It is {@code @Disabled} because it is EXPECTED TO FAIL: that failure is the whole point. The inherited test
 * asserts the same invariants as the showcase, and against a lock that takes no row lock they do not hold (cached
 * balances stop matching the ledger and money appears from nowhere). To watch it, delete the {@code @Disabled} line and
 * run {@code ./gradlew test --tests '*LockingOffDemoIntegrationTest'}. The recorded result of doing exactly that is in
 * docs/evidence/phase-4-locking-off-demo.txt and docs/CV_EVIDENCE.md.
 */
@Disabled("Expected to fail: shows lost updates when the row lock is switched off. Remove this line to run it by hand.")
@Import(TestLockServices.NaiveLockConfig.class)
class LockingOffDemoIntegrationTest extends ConcurrentTransferShowcaseIntegrationTest {}
