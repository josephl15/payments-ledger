package dev.joseph.ledger;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.AccountType;
import dev.joseph.ledger.repository.AccountRepository;
import dev.joseph.ledger.service.AccountLockService;
import dev.joseph.ledger.service.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately broken replacements for {@link AccountLockService}, used ONLY by tests to show that the concurrency
 * tests can fail. They live in the test source tree and are switched on by importing one of the configuration
 * classes below into a single test class; the shipped application has no switch, flag or property that turns the real
 * locking off.
 *
 * <p>How a replacement gets used: each configuration declares a bean of type AccountLockService marked
 * {@code @Primary}. When Spring builds TransferService and needs an AccountLockService it finds two candidates (the
 * real one and this one) and picks the {@code @Primary} one. The bean methods have their own names so they do not
 * replace the real bean's name, which Spring Boot forbids by default.
 */
final class TestLockServices {

    private TestLockServices() {}

    /**
     * "Locking off": the Phase 3 body, a plain read that takes no row lock. Two transfers touching one account read the
     * same balance and the later write overwrites the earlier one.
     */
    static class NaiveAccountLockService extends AccountLockService {

        private final AccountRepository accounts;

        NaiveAccountLockService(AccountRepository accounts) {
            super(accounts);
            this.accounts = accounts;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Map<UUID, Account> lock(Set<UUID> customerAccountIds) {
            Map<UUID, Account> found = new LinkedHashMap<>();
            for (UUID id : customerAccountIds) {
                Optional<Account> account = accounts.findById(id);
                if (account.isPresent() && account.get().getType() == AccountType.CUSTOMER) {
                    found.put(id, account.get());
                }
            }
            if (found.size() != customerAccountIds.size()) {
                throw new ResourceNotFoundException("Account not found");
            }
            return found;
        }
    }

    /**
     * "Locks taken in an inconsistent order": it does lock every row (FOR UPDATE), but one account at a time, and it
     * alternates between ascending and descending order from call to call. Two concurrent transfers between the same
     * two accounts then often lock them in opposite orders, each holding one row and waiting for the other, which
     * PostgreSQL resolves by aborting one of them with a deadlock error (SQLSTATE 40P01).
     */
    static class MixedOrderAccountLockService extends AccountLockService {

        private final AccountRepository accounts;
        private final AtomicInteger calls = new AtomicInteger();

        MixedOrderAccountLockService(AccountRepository accounts) {
            super(accounts);
            this.accounts = accounts;
        }

        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public Map<UUID, Account> lock(Set<UUID> customerAccountIds) {
            List<UUID> order = new ArrayList<>(customerAccountIds);
            order.sort(Comparator.naturalOrder());
            if (calls.getAndIncrement() % 2 == 1) {
                Collections.reverse(order);
            }
            Map<UUID, Account> found = new LinkedHashMap<>();
            for (UUID id : order) {
                for (Account account : accounts.lockCustomerAccountsOrderedById(Set.of(id))) {
                    found.put(account.getId(), account);
                }
            }
            if (found.size() != customerAccountIds.size()) {
                throw new ResourceNotFoundException("Account not found");
            }
            return found;
        }
    }

    @TestConfiguration
    static class NaiveLockConfig {
        @Bean
        @Primary
        AccountLockService naiveAccountLockService(AccountRepository accounts) {
            return new NaiveAccountLockService(accounts);
        }
    }

    @TestConfiguration
    static class MixedOrderLockConfig {
        @Bean
        @Primary
        AccountLockService mixedOrderAccountLockService(AccountRepository accounts) {
            return new MixedOrderAccountLockService(accounts);
        }
    }
}
