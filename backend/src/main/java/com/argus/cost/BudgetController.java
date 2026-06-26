package com.argus.cost;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 6 — Cost Governor budget status (Epic 10). Session-gated like all /api. */
@RestController
@RequestMapping("/api/budget")
public class BudgetController {

	private final CostGovernor governor;

	public BudgetController(CostGovernor governor) {
		this.governor = governor;
	}

	@GetMapping("/status")
	public BudgetStatus status() {
		return governor.status();
	}
}
