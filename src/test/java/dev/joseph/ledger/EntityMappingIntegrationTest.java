package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.AccountStatus;
import dev.joseph.ledger.domain.AccountType;
import dev.joseph.ledger.domain.AuditLogEntry;
import dev.joseph.ledger.domain.AuditOutcome;
import dev.joseph.ledger.domain.IdempotencyKey;
import dev.joseph.ledger.domain.LedgerEntry;
import dev.joseph.ledger.domain.LedgerTransaction;
import dev.joseph.ledger.domain.Role;
import dev.joseph.ledger.domain.SystemAccountIds;
import dev.joseph.ledger.domain.TransactionType;
import dev.joseph.ledger.domain.User;
import dev.joseph.ledger.repository.AccountRepository;
import dev.joseph.ledger.repository.AuditLogRepository;
import dev.joseph.ledger.repository.IdempotencyKeyRepository;
import dev.joseph.ledger.repository.LedgerEntryRepository;
import dev.joseph.ledger.repository.LedgerTransactionRepository;
import dev.joseph.ledger.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Hibernate mappings are correct, not just accepted. The application context starting at all proves
 * {@code ddl-auto=validate} passed, but validate is lenient (it accepts a primitive {@code long} on a nullable
 * column, an Instant on a plain timestamp column, and so on), so each entity is saved and loaded back in a fresh
 * transaction and compared field by field.
 *
 * <p>Each step runs in its own committed transaction (TransactionTemplate), so every load really reads the
 * database instead of the persistence context. Timestamps are truncated to microseconds because PostgreSQL stores
 * microseconds while {@code Instant.now()} can carry more.
 */
class EntityMappingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired LedgerTransactionRepository transactions;
    @Autowired LedgerEntryRepository entries;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired AuditLogRepository audit;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @PersistenceContext EntityManager em;

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private User saveUser() {
        return tx.execute(s -> users.save(new User("user-" + UUID.randomUUID(), "hash", Role.USER, now())));
    }

    @Test
    void userRoundTrips() {
        Instant created = now();
        String name = "user-" + UUID.randomUUID();
        UUID id = tx.execute(s -> users.save(new User(name, "$2a$10$abcdefghijklmnopqrstuv", Role.ADMIN, created)).getId());
        assertThat(id).isNotNull();

        User loaded = tx.execute(s -> users.findById(id).orElseThrow());
        assertThat(loaded.getUsername()).isEqualTo(name);
        assertThat(loaded.getPasswordHash()).isEqualTo("$2a$10$abcdefghijklmnopqrstuv");
        assertThat(loaded.getRole()).isEqualTo(Role.ADMIN);
        assertThat(loaded.getCreatedAt()).isEqualTo(created);
        assertThat(tx.execute(s -> users.findByUsername(name)).orElseThrow().getId()).isEqualTo(id);
    }

    @Test
    void customerAccountRoundTripsAndBalanceChangeIsSaved() {
        User owner = saveUser();
        Instant created = now();
        UUID id = tx.execute(s -> accounts
                .save(Account.newCustomerAccount(owner.getId(), "Savings", "GBP", created))
                .getId());

        Account loaded = tx.execute(s -> accounts.findById(id).orElseThrow());
        assertThat(loaded.getOwnerUserId()).isEqualTo(owner.getId());
        assertThat(loaded.getType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(loaded.getName()).isEqualTo("Savings");
        assertThat(loaded.getCurrency()).isEqualTo("GBP");
        assertThat(loaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(loaded.getBalanceMinor()).isZero();
        assertThat(loaded.getCreatedAt()).isEqualTo(created);

        // A managed entity changed inside a transaction is written back at commit (dirty checking).
        tx.executeWithoutResult(s -> accounts.findById(id).orElseThrow().applyDelta(1_250));
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, id))
                .isEqualTo(1_250L);
        assertThat(tx.<List<Account>>execute(s -> accounts.findByOwnerUserId(owner.getId()))).hasSize(1);
    }

    @Test
    void seededSystemAccountsLoadWithNullBalanceAndTheConstantsMatchTheDatabase() {
        Account funding = tx.execute(s -> accounts.findById(SystemAccountIds.EXTERNAL_FUNDING).orElseThrow());
        Account payouts = tx.execute(s -> accounts.findById(SystemAccountIds.EXTERNAL_PAYOUTS).orElseThrow());

        assertThat(funding.getName()).isEqualTo("EXTERNAL_FUNDING");
        assertThat(payouts.getName()).isEqualTo("EXTERNAL_PAYOUTS");
        for (Account system : List.of(funding, payouts)) {
            assertThat(system.getType()).isEqualTo(AccountType.SYSTEM);
            assertThat(system.isSystem()).isTrue();
            assertThat(system.getBalanceMinor()).isNull();
            assertThat(system.getOwnerUserId()).isNull();
            assertThat(system.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }
        // The constants are the same ids the migration inserted, looked up by name rather than by our constant.
        assertThat(jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'EXTERNAL_FUNDING'", UUID.class))
                .isEqualTo(SystemAccountIds.EXTERNAL_FUNDING);
        assertThat(jdbc.queryForObject("SELECT id FROM accounts WHERE name = 'EXTERNAL_PAYOUTS'", UUID.class))
                .isEqualTo(SystemAccountIds.EXTERNAL_PAYOUTS);
    }

    @Test
    void systemAccountRefusesABalanceChange() {
        Account funding = tx.execute(s -> accounts.findById(SystemAccountIds.EXTERNAL_FUNDING).orElseThrow());
        assertThatThrownBy(() -> funding.applyDelta(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no cached balance");
    }

    @Test
    void transactionAndEntriesRoundTripWithGeneratedIds() {
        User user = saveUser();
        UUID account = tx.execute(s -> accounts
                .save(Account.newCustomerAccount(user.getId(), "Current", "GBP", now()))
                .getId());
        Instant created = now();

        UUID txId = tx.execute(s -> {
            // Save the transaction first: the entries reference it by foreign key.
            UUID id = transactions
                    .save(new LedgerTransaction(TransactionType.DEPOSIT, "ref-1", null, user.getId(), created))
                    .getId();
            LedgerEntry debit = entries.save(new LedgerEntry(id, SystemAccountIds.EXTERNAL_FUNDING, -700, "GBP", created));
            LedgerEntry credit = entries.save(new LedgerEntry(id, account, 700, "GBP", created));
            // IDENTITY ids are known straight after save, before commit.
            assertThat(debit.getId()).isNotNull();
            assertThat(credit.getId()).isGreaterThan(debit.getId());
            return id;
        });

        LedgerTransaction loadedTx = tx.execute(s -> transactions.findById(txId).orElseThrow());
        assertThat(loadedTx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(loadedTx.getReference()).isEqualTo("ref-1");
        assertThat(loadedTx.getReversesTransactionId()).isNull();
        assertThat(loadedTx.getCreatedByUserId()).isEqualTo(user.getId());
        assertThat(loadedTx.getCreatedAt()).isEqualTo(created);

        List<LedgerEntry> loaded = tx.execute(s -> entries.findByTransactionId(txId));
        assertThat(loaded).hasSize(2);
        assertThat(loaded).extracting(LedgerEntry::getAmountMinor).containsExactlyInAnyOrder(-700L, 700L);
        assertThat(loaded).allSatisfy(e -> {
            assertThat(e.getCurrency()).isEqualTo("GBP");
            assertThat(e.getCreatedAt()).isEqualTo(created);
        });
        assertThat(tx.<List<LedgerEntry>>execute(s -> entries.findByAccountIdOrderByIdDesc(account))).hasSize(1);
    }

    @Test
    void reversalTransactionRoundTripsAndSecondReversalIsBlockedAndDetected() {
        User user = saveUser();
        UUID original = tx.execute(s -> transactions
                .save(new LedgerTransaction(TransactionType.TRANSFER, null, null, user.getId(), now()))
                .getId());
        assertThat(tx.<Boolean>execute(s -> transactions.existsByReversesTransactionId(original))).isFalse();

        UUID reversal = tx.execute(s -> transactions
                .save(new LedgerTransaction(TransactionType.REVERSAL, null, original, user.getId(), now()))
                .getId());
        assertThat(tx.<UUID>execute(s -> transactions.findById(reversal).orElseThrow().getReversesTransactionId()))
                .isEqualTo(original);
        assertThat(tx.<Boolean>execute(s -> transactions.existsByReversesTransactionId(original))).isTrue();

        assertThatThrownBy(() -> tx.execute(s -> transactions.save(
                        new LedgerTransaction(TransactionType.REVERSAL, null, original, user.getId(), now()))))
                .satisfies(e -> assertThat(constraintName(e)).isEqualTo("uq_ledger_tx_reverses"));
    }

    @Test
    void immutableEntryIsNeverUpdatedByHibernate() {
        User user = saveUser();
        UUID account = tx.execute(s -> accounts
                .save(Account.newCustomerAccount(user.getId(), "Current", "GBP", now()))
                .getId());
        UUID txId = tx.execute(s -> transactions
                .save(new LedgerTransaction(TransactionType.DEPOSIT, null, null, user.getId(), now()))
                .getId());
        long entryId = tx.execute(s -> entries.save(new LedgerEntry(txId, account, 100, "GBP", now())).getId());

        // Change the field behind Hibernate's back and flush. Because the entity is @Immutable (and its columns are
        // not updatable) Hibernate sends no UPDATE; if it did, the database trigger would raise an error here.
        tx.executeWithoutResult(s -> {
            LedgerEntry entry = entries.findById(entryId).orElseThrow();
            ReflectionTestUtils.setField(entry, "amountMinor", 999L);
            em.flush();
        });

        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ledger_entries WHERE id = ?", Long.class, entryId))
                .isEqualTo(100L);
    }

    @Test
    void idempotencyKeyRoundTripsWithJsonBodyStoredAsAnObject() throws Exception {
        User user = saveUser();
        Instant created = now();
        Instant expires = created.plus(24, ChronoUnit.HOURS);
        Long id = tx.execute(s -> keys.save(new IdempotencyKey(user.getId(), "key-1", "a".repeat(64), created, expires))
                .getId());

        IdempotencyKey fresh = tx.execute(s -> keys.findByUserIdAndIdemKey(user.getId(), "key-1").orElseThrow());
        assertThat(fresh.getResponseStatus()).isNull();
        assertThat(fresh.getResponseBody()).isNull();
        assertThat(fresh.getTransactionId()).isNull();
        assertThat(fresh.getExpiresAt()).isEqualTo(expires);

        UUID txId = tx.execute(s -> transactions
                .save(new LedgerTransaction(TransactionType.DEPOSIT, null, null, user.getId(), now()))
                .getId());
        tx.executeWithoutResult(s ->
                keys.findByUserIdAndIdemKey(user.getId(), "key-1").orElseThrow().complete(201, "{\"b\": [1,2],   \"a\":\"x\"}", txId));

        IdempotencyKey done = tx.execute(s -> keys.findByUserIdAndIdemKey(user.getId(), "key-1").orElseThrow());
        assertThat(done.getId()).isEqualTo(id);
        assertThat(done.getResponseStatus()).isEqualTo(201);
        assertThat(done.getTransactionId()).isEqualTo(txId);
        // JSONB normalises key order and spacing, so compare the JSON content, not the text.
        JsonNode expected = json.readTree("{\"a\":\"x\",\"b\":[1,2]}");
        assertThat(json.readTree(done.getResponseBody())).isEqualTo(expected);
        // Stored as a JSON object, not as a JSON string containing escaped JSON.
        assertThat(jdbc.queryForObject(
                        "SELECT response_body ->> 'a' FROM idempotency_keys WHERE id = ?", String.class, id))
                .isEqualTo("x");
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedWithTheConstraintName() {
        User user = saveUser();
        Instant created = now();
        tx.execute(s -> keys.save(new IdempotencyKey(user.getId(), "dup", "b".repeat(64), created, created.plusSeconds(60))));

        assertThatThrownBy(() -> tx.execute(s ->
                        keys.save(new IdempotencyKey(user.getId(), "dup", "b".repeat(64), created, created.plusSeconds(60)))))
                .satisfies(e -> assertThat(constraintName(e)).isEqualTo("uq_idempotency_user_key"));
    }

    @Test
    void auditEntryRoundTripsWithStructuredDetails() {
        User user = saveUser();
        UUID resource = UUID.randomUUID();
        Instant created = now();
        Long id = tx.execute(s -> audit.save(new AuditLogEntry(
                        user.getId(),
                        "TRANSFER",
                        "LEDGER_TRANSACTION",
                        resource,
                        AuditOutcome.DENIED,
                        Map.of("reason", "INSUFFICIENT_FUNDS", "attempts", 2),
                        created))
                .getId());
        assertThat(id).isNotNull();

        List<AuditLogEntry> found = tx.execute(s -> audit.findByUserIdOrderByIdDesc(user.getId()));
        assertThat(found).hasSize(1);
        AuditLogEntry entry = found.get(0);
        assertThat(entry.getAction()).isEqualTo("TRANSFER");
        assertThat(entry.getResourceType()).isEqualTo("LEDGER_TRANSACTION");
        assertThat(entry.getResourceId()).isEqualTo(resource);
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(entry.getDetails()).containsEntry("reason", "INSUFFICIENT_FUNDS").containsEntry("attempts", 2);
        assertThat(entry.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void auditEntryWithNoUserOrDetailsRoundTrips() {
        Long id = tx.execute(s -> audit.save(new AuditLogEntry(
                        null, "LOGIN", "USER", null, AuditOutcome.FAILED, null, now()))
                .getId());
        assertThat(jdbc.queryForObject("SELECT user_id IS NULL AND details IS NULL FROM audit_log WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    /** Walks the cause chain to Hibernate's constraint exception and returns the violated constraint name. */
    private static String constraintName(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
        }
        throw new AssertionError("no ConstraintViolationException in the cause chain", failure);
    }
}
