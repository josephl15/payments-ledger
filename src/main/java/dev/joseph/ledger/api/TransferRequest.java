package dev.joseph.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Body of POST /api/transfers. See {@link DepositRequest} for why the amount is a boxed Long. */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @Positive Long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 255) String reference) {}
