package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.Currencies;
import dev.joseph.ledger.repository.AccountRepository;
import dev.joseph.ledger.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening, listing and viewing a customer's own accounts. */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final UserRepository users;
    private final Clock clock;

    public AccountService(AccountRepository accounts, UserRepository users, Clock clock) {
        this.accounts = accounts;
        this.users = users;
        this.clock = clock;
    }

    /** Opens a new ACTIVE GBP account with a zero balance for the acting user. */
    @Transactional
    public Account open(ActingUser actor, String name) {
        if (!users.existsById(actor.userId())) {
            throw new ResourceNotFoundException("User not found");
        }
        return accounts.save(Account.newCustomerAccount(actor.userId(), name, Currencies.GBP, clock.instant()));
    }

    /** The acting user's accounts, oldest first. Never includes anyone else's. */
    @Transactional(readOnly = true)
    public List<Account> list(ActingUser actor) {
        return accounts.findByOwnerUserIdOrderByCreatedAtAscIdAsc(actor.userId());
    }

    /** One of the acting user's accounts; another user's account is reported as not found. */
    @Transactional(readOnly = true)
    public Account get(ActingUser actor, UUID accountId) {
        return accounts.findById(accountId)
                .filter(account -> actor.userId().equals(account.getOwnerUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
    }
}
