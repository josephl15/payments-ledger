package dev.joseph.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of POST /api/deposits. {@code amountMinor} is a boxed {@code Long}, not a primitive {@code long}: a missing
 * field then arrives as null and is rejected by {@code @NotNull}, instead of silently becoming 0. Together with
 * Jackson's accept-float-as-int=false, "30.9" and "3000" (a string) are rejected too.
 */
public record DepositRequest(
        @NotNull UUID accountId,
        @NotNull @Positive Long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 255) String reference) {}
