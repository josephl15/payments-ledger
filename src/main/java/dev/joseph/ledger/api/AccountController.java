package dev.joseph.ledger.api;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.security.CurrentUserProvider;
import dev.joseph.ledger.service.AccountService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller: it turns HTTP into a service call and the result into a response. No business rules live here.
 * {@code @RestController} makes Spring call these methods for matching requests and write the return value as JSON;
 * The caller comes from the verified token (CurrentUserProvider), never from a header the client can choose.
 * {@code @Valid} runs the Bean Validation annotations on the request record before the method body starts.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final CurrentUserProvider currentUser;

    public AccountController(AccountService accountService, CurrentUserProvider currentUser) {
        this.accountService = accountService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.open(currentUser.current(), request.name());
        return ResponseEntity.created(URI.create("/api/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.list(currentUser.current()).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(currentUser.current(), id));
    }
}
