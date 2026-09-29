package dev.joseph.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/accounts. New accounts are always GBP. */
public record OpenAccountRequest(@NotBlank @Size(max = 100) String name) {}
