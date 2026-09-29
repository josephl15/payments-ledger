package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.joseph.ledger.repository.AccountRepository;
import dev.joseph.ledger.repository.AuditLogRepository;
import dev.joseph.ledger.repository.IdempotencyKeyRepository;
import dev.joseph.ledger.repository.LedgerEntryRepository;
import dev.joseph.ledger.repository.LedgerTransactionRepository;
import dev.joseph.ledger.repository.UserRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

/**
 * Guards the "no delete" design. If someone later adds {@code void deleteById(Long id)} to an append-only
 * repository (or makes it extend JpaRepository), Spring Data would happily implement it and only the database
 * trigger would stop it at runtime. This test fails at build time instead. No Spring context or database needed.
 */
class ImmutableRepositoryShapeTest {

    private static final List<Class<?>> APPEND_ONLY = List.of(
            LedgerEntryRepository.class, AuditLogRepository.class, LedgerTransactionRepository.class);

    private static final List<Class<?>> ALL = List.of(
            LedgerEntryRepository.class,
            AuditLogRepository.class,
            LedgerTransactionRepository.class,
            UserRepository.class,
            AccountRepository.class,
            IdempotencyKeyRepository.class);

    @Test
    void appendOnlyRepositoriesExposeNoDeleteOrRemoveMethod() {
        for (Class<?> repository : APPEND_ONLY) {
            assertThat(Arrays.stream(repository.getMethods()).map(Method::getName))
                    .as(repository.getSimpleName())
                    .noneMatch(name -> name.startsWith("delete") || name.startsWith("remove"));
        }
    }

    @Test
    void noRepositoryExtendsJpaRepositoryOrCrudRepository() {
        for (Class<?> repository : ALL) {
            assertThat(JpaRepository.class.isAssignableFrom(repository))
                    .as("%s extends JpaRepository", repository.getSimpleName())
                    .isFalse();
            assertThat(CrudRepository.class.isAssignableFrom(repository))
                    .as("%s extends CrudRepository", repository.getSimpleName())
                    .isFalse();
        }
    }
}
