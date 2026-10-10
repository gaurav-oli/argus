package com.argus.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo Mode toggle — global setting; writes are admin-only (S-A3 / H1). */
@RestController
@RequestMapping("/api/settings/demo-mode")
public class DemoModeController {

	private final DemoModeService demoMode;
	private final CurrentUserService currentUser;

	public DemoModeController(DemoModeService demoMode, CurrentUserService currentUser) {
		this.demoMode = demoMode;
		this.currentUser = currentUser;
	}

	@GetMapping
	public DemoModeService.View get() {
		return demoMode.current();
	}

	@PutMapping
	public DemoModeService.View update(@RequestBody DemoModeService.View body, HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return demoMode.update(body);
	}
}
