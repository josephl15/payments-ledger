package dev.joseph.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A place money can sit. The cached balance is a fast-read copy of the sum of the account's ledger entries; it is
 * changed only in the same database transaction as the entries it reflects, and reconciliation checks it.
 *
 * <p>{@code balanceMinor} is a boxed {@code Long} on purpose: it is NULL for SYSTEM accounts, and a primitive
 * {@code long} would pass schema validation but throw a NullPointerException when a SYSTEM account is loaded.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", updatable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private AccountType type;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "balance_minor")
    private Long balanceMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    private Account(
            UUID ownerUserId, AccountType type, String name, String currency, Long balanceMinor, Instant createdAt) {
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.name = name;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.balanceMinor = balanceMinor;
        this.createdAt = createdAt;
    }

    /** A new ACTIVE customer account with a zero cached balance. */
    public static Account newCustomerAccount(UUID ownerUserId, String name, String currency, Instant createdAt) {
        return new Account(ownerUserId, AccountType.CUSTOMER, name, currency, 0L, createdAt);
    }

    public boolean isSystem() {
        return type == AccountType.SYSTEM;
    }

    /**
     * Adds a signed amount to the cached balance. Throws on a SYSTEM account (which has no cached balance) and on
     * arithmetic overflow. It does not check for overdraft: the service does that under the row lock, and the
     * database CHECK is the backstop.
     */
    public void applyDelta(long delta) {
        if (isSystem()) {
            throw new IllegalStateException("System accounts have no cached balance");
        }
        this.balanceMinor = Math.addExact(this.balanceMinor, delta);
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public AccountType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    /** The cached balance in pence, or null for a SYSTEM account. */
    public Long getBalanceMinor() {
        return balanceMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
