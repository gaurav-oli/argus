package com.argus.portfolio;

import com.argus.security.CurrentUserContext;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Resolves the Hibernate "tenant" — really just the signed-in person — for every
 * {@code @TenantId}-annotated portfolio entity, from the request/job-scoped
 * {@link CurrentUserContext}. This is what makes per-user portfolio isolation automatic: once an
 * entity carries {@code @TenantId}, Hibernate adds this id to every query AND stamps it on every
 * insert, for every repository method, with no per-query code to get wrong or forget.
 *
 * <p>When nobody is signed in (e.g. a request that reached here before auth, or a background job that
 * didn't call {@code CurrentUserContext.runAs}), this resolves to {@link #NO_USER} — a tenant id no
 * real {@link com.argus.security.AppUser} ever has (ids start at 1) — so such access sees zero rows
 * of anyone's portfolio data rather than accidentally seeing everyone's. Financial data fails closed.
 */
@Component
public class PortfolioTenantResolver implements CurrentTenantIdentifierResolver<Long> {

	static final Long NO_USER = 0L;

	@Override
	public Long resolveCurrentTenantIdentifier() {
		Long id = CurrentUserContext.get();
		return id != null ? id : NO_USER;
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		// A pooled/virtual thread must never reuse a Hibernate Session opened under a different
		// tenant — fail loudly instead of silently mixing two people's portfolio data.
		return true;
	}
}
