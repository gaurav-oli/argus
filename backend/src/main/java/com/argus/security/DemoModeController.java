package com.argus.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo Mode toggle (session-gated under {@code /api/settings/demo-mode}). */
@RestController
@RequestMapping("/api/settings/demo-mode")
public class DemoModeController {

	private final DemoModeService demoMode;

	public DemoModeController(DemoModeService demoMode) {
		this.demoMode = demoMode;
	}

	@GetMapping
	public DemoModeService.View get() {
		return demoMode.current();
	}

	@PutMapping
	public DemoModeService.View update(@RequestBody DemoModeService.View body) {
		return demoMode.update(body);
	}
}
