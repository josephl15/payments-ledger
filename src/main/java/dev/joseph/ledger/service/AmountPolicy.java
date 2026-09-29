package dev.joseph.ledger.service;

import dev.joseph.ledger.config.LedgerProperties;
import dev.joseph.ledger.domain.Currencies;
import org.springframework.stereotype.Component;

/**
 * The rules about a single amount and currency, kept in one place so deposits and transfers cannot drift apart.
 * The controller's Bean Validation already rejects null and non-positive amounts; this is the service's own check, so
 * the rule holds for any caller (a test, a later scheduled job), and it is where the configurable maximum lives.
 */
@Component
public class AmountPolicy {

    private final LedgerProperties properties;

    public AmountPolicy(LedgerProperties properties) {
        this.properties = properties;
    }

    /** Amount must be at least 1 penny and at most the configured maximum. */
    public void checkAmount(long amountMinor) {
        if (amountMinor <= 0) {
            throw new InvalidRequestException("amountMinor must be greater than zero");
        }
        if (amountMinor > properties.maxAmountMinor()) {
            throw new InvalidRequestException("amountMinor must not exceed " + properties.maxAmountMinor());
        }
    }

    /** Only GBP exists in this ledger. */
    public void checkCurrency(String currency) {
        if (!Currencies.GBP.equals(currency)) {
            throw new InvalidRequestException("Only GBP is supported");
        }
    }
}
