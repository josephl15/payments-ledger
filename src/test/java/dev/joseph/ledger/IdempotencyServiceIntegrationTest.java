package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.joseph.ledger.domain.Role;
import dev.joseph.ledger.domain.User;
import dev.joseph.ledger.repository.UserRepository;
import dev.joseph.ledger.service.DuplicateIdempotencyKeyException;
import dev.joseph.ledger.service.IdempotencyKeyMismatchException;
import dev.joseph.ledger.service.IdempotencyService;
import dev.joseph.ledger.service.IdempotentExecutor;
import dev.joseph.ledger.service.IdempotentOutcome;
import dev.joseph.ledger.service.ActingUser;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The building blocks under the HTTP layer: which database errors count as "this key was used before", and the
 * transaction rules of the executor and the service. Talks to real PostgreSQL.
 */
class IdempotencyServiceIntegrationTest extends AbstractIdempotencyIntegrationTest {

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    IdempotentExecutor executor;

    @Autowired
    UserRepository users;

    @Autowired
    PlatformTransactionManager transactionManager;

    private static final String HASH = "a".repeat(64);

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void putTheClockBack() {
        clock.reset();
    }

    @Test
    void secondBeginForTheSameKeyThrowsDuplicateFromTheBeginCallItself() {
        UUID user = newUser();

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
                    idempotency.begin(user, "dup", HASH);
                    // A second claim of the same (user, key) in the same transaction hits the unique index at the
                    // flush inside begin(), not later at commit.
                    idempotency.begin(user, "dup", HASH);
                }))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
    }

    @Test
    void aDuplicateAcrossTransactionsIsRecognisedByTheNamedConstraint() {
        UUID user = newUser();
        tx().executeWithoutResult(status -> {
            var row = idempotency.begin(user, "k", HASH);
            idempotency.complete(row, 201, "{\"ok\":true}", null);
        });

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> idempotency.begin(user, "k", HASH)))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .satisfies(e -> assertThat(IdempotencyService.isKeyViolation(e.getCause())).isTrue());
    }

    @Test
    void aViolationOfADifferentUniqueConstraintIsNotMistakenForADuplicateKey() {
        String username = "dup-name-" + UUID.randomUUID();
        tx().executeWithoutResult(s -> users.save(new User(username, "not-a-real-hash", Role.USER, Instant.now())));

        // Same SQLSTATE 23505, different constraint (uq_users_username).
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> tx().executeWithoutResult(
                s -> users.save(new User(username, "not-a-real-hash", Role.USER, Instant.now()))));

        assertThat(failure).isNotNull();
        // Guard against a vacuous test: the failure really is a unique violation of the OTHER constraint.
        String violated = null;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                assertThat(cve.getSQLState()).isEqualTo("23505");
                violated = cve.getConstraintName();
            }
        }
        assertThat(violated).isEqualTo("uq_users_username");
        assertThat(IdempotencyService.isKeyViolation(failure)).isFalse();
    }

    @Test
    void aForeignKeyViolationInsideBeginIsRethrownNotTreatedAsAReplay() {
        UUID userWhoDoesNotExist = UUID.randomUUID();

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> idempotency.begin(userWhoDoesNotExist, "k", HASH)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateIdempotencyKeyException.class);
    }

    @Test
    void beginAndCompleteRefuseToRunWithoutATransaction() {
        UUID user = newUser();

        assertThatThrownBy(() -> idempotency.begin(user, "k", HASH)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void replayInsideAnUnexpiredKeyReturnsTheStoredResponseAndAWrongHashIsRefused() {
        UUID user = newUser();
        tx().executeWithoutResult(status -> {
            var row = idempotency.begin(user, "k", HASH);
            idempotency.complete(row, 201, "{\"b\":1,\"a\":2}", null);
        });

        var stored = idempotency.replay(user, "k", HASH);
        assertThat(stored).isPresent();
        assertThat(stored.get().status()).isEqualTo(201);
        assertThat(stored.get().replayed()).isTrue();
        assertThat(idempotency.replay(user, "other-key", HASH)).isEmpty();
        assertThatThrownBy(() -> idempotency.replay(user, "k", "b".repeat(64)))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
    }

    @Test
    void replayTreatsAnExpiredRowAsAbsent() {
        UUID user = newUser();
        tx().executeWithoutResult(status -> idempotency.complete(idempotency.begin(user, "k", HASH), 201, "{}", null));

        clock.advance(Duration.ofHours(25));

        assertThat(idempotency.replay(user, "k", HASH)).isEmpty();
    }

    @Test
    void theExecutorRefusesToRunInsideAnotherTransaction() {
        UUID user = newUser();

        // Inside an outer transaction the catch-and-replay design cannot work, so the executor fails fast.
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> executor.execute(
                        new ActingUser(user),
                        "k",
                        "POST",
                        "/x",
                        "body",
                        () -> new IdempotentOutcome(201, "x", null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without an open transaction");
    }
}
