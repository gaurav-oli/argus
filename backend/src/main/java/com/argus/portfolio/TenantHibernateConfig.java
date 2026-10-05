package com.argus.portfolio;

import java.util.Map;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Wires {@link PortfolioTenantResolver} into Hibernate so every {@code @TenantId} field is resolved
 * against it. The property must be set to the live resolver bean (not a class name string), which is
 * why this needs a {@link HibernatePropertiesCustomizer} rather than a plain {@code application.yml} entry.
 */
@Component
public class TenantHibernateConfig implements HibernatePropertiesCustomizer {

	private final PortfolioTenantResolver resolver;

	public TenantHibernateConfig(PortfolioTenantResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void customize(Map<String, Object> hibernateProperties) {
		hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
	}
}
