package dev.joseph.ledger.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed settings under the {@code ledger.} prefix. Spring binds {@code ledger.max-amount-minor} from
 * application.yml (or the environment variable LEDGER_MAX_AMOUNT_MINOR) into this record at startup.
 *
 * @param maxAmountMinor largest single deposit or transfer in pence; the default is 100,000,000 (one million pounds)
 */
@Validated
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(@DefaultValue("100000000") @Positive long maxAmountMinor) {}
