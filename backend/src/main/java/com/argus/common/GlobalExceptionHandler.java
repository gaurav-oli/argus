package com.argus.common;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
}
