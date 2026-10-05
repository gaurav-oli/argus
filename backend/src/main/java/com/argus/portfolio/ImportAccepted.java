package com.argus.portfolio;

/**
 * Response for an automatic ({@code mode=auto}) statement upload: the parse is queued on
 * {@link StatementImportRunner}'s background executor rather than blocking the request on several
 * sequential local-model calls. No import id yet — one doesn't exist until the background parse
 * finishes — the uploader is told what happened next by push notification and email instead.
 */
public record ImportAccepted(String status, String message) {
}
