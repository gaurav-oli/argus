package com.argus.cost;

import com.argus.security.CurrentUserContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S-C3 — the signed-in person's AI and import use today against their caps (cap 0 = unlimited). */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

	private final UsageQuota quota;

	public QuotaController(UsageQuota quota) {
		this.quota = quota;
	}

	@GetMapping
	public Map<String, UsageQuota.Usage> today() {
		return quota.today(CurrentUserContext.get());
	}
}
