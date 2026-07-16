package com.argus.portfolio;

/**
 * Owner identity for one account, extracted from a statement header (Story 3.1 follow-up). The
 * {@code account} label matches the one on the account's holdings/cash; {@code ownerType} is
 * {@code Joint}, {@code Solo} or {@code Corporate} and {@code ownerName} the holder name(s).
 * {@code accountType} is the normalized registration type (TFSA/RRSP/RESP/Cash/Corporate/…) used to
 * group accounts across banks. Persisted to {@link AccountMeta} on import confirm; any field may be
 * null when the parser couldn't tell.
 */
public record ParsedAccount(String account, String ownerType, String ownerName, String accountType) {
}
