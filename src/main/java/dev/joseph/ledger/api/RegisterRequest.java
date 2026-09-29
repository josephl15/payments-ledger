package dev.joseph.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/auth/register. Usernames are 3 to 32 letters, digits, dots, dashes or underscores. The password
 * is 8 to 72 characters (72 because BCrypt ignores anything after 72 bytes).
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "[A-Za-z0-9._-]*", message = "may only contain letters, digits, '.', '_' and '-'")
                String username,
        @NotBlank @Size(min = 8, max = 72) String password) {

    /** A record's generated toString prints every field; this keeps the password out of any log line or stack trace. */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=****]";
    }
}
