package com.argus.portfolio;

/**
 * Owner identity for one account, extracted from a statement header (Story 3.1 follow-up). The
 * {@code account} label matches the one on the account's holdings/cash; {@code ownerType} is
 * {@code Joint} or {@code Solo} and {@code ownerName} the holder name(s). Persisted to
 * {@link AccountMeta} on import confirm; either owner field may be null when the parser couldn't tell.
 */
public record ParsedAccount(String account, String ownerType, String ownerName) {
}
