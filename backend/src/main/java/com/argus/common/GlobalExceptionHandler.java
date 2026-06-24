package com.argus.common;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single source of error responses: every error becomes an RFC 9457 {@link ProblemDetail}
 * ({@code application/problem+json}). Extends {@link ResponseEntityExceptionHandler} so Spring's
 * own MVC exceptions are also rendered as problem details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Resource Not Found");
		problem.setType(URI.create("https://argus.local/problems/not-found"));
		return problem;
	}

	@ExceptionHandler(UnauthorizedException.class)
	ProblemDetail handleUnauthorized(UnauthorizedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
		problem.setTitle("Unauthorized");
		problem.setType(URI.create("https://argus.local/problems/unauthorized"));
		return problem;
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail handleConflict(ConflictException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		problem.setType(URI.create("https://argus.local/problems/conflict"));
		return problem;
	}

	@ExceptionHandler(BadRequestException.class)
	ProblemDetail handleBadRequest(BadRequestException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Bad Request");
		problem.setType(URI.create("https://argus.local/problems/bad-request"));
		return problem;
	}

	// Note: the servlet-level MaxUploadSizeExceededException is already mapped to a 413 ProblemDetail
	// by the ResponseEntityExceptionHandler base class — we only add the controller-level guard here.
	@ExceptionHandler(PayloadTooLargeException.class)
	ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
		problem.setTitle("Payload Too Large");
		problem.setType(URI.create("https://argus.local/problems/payload-too-large"));
		return problem;
	}

	// Model Gateway can't produce a response — e.g. escalation requested but Haiku is unavailable
	// (no API key), or a Haiku call failed (Story 7.3). 503 so the client shows an error/retry,
	// never placeholder text as a success.
	@ExceptionHandler(com.argus.model.ModelGatewayException.class)
	ProblemDetail handleModelUnavailable(com.argus.model.ModelGatewayException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
		problem.setTitle("Model Unavailable");
		problem.setType(URI.create("https://argus.local/problems/model-unavailable"));
		return problem;
	}

	@ExceptionHandler(com.argus.security.LockedException.class)
	ResponseEntity<ProblemDetail> handleLocked(com.argus.security.LockedException ex) {
		HttpStatus status = ex.isFull() ? HttpStatus.LOCKED : HttpStatus.TOO_MANY_REQUESTS;
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
		problem.setTitle(ex.isFull() ? "Locked" : "Too Many Attempts");
		problem.setType(URI.create("https://argus.local/problems/locked"));
		problem.setProperty("fullyLocked", ex.isFull());
		problem.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());

		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (!ex.isFull()) {
			builder.header("Retry-After", Long.toString(ex.getRetryAfterSeconds()));
		}
		return builder.body(problem);
	}
}
