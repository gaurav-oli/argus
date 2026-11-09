package com.argus.grading;

import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report cards and strategy gates (strategy rebuild). Reads are open to signed-in users for the Agents page; pinning
 * a weight and forcing a grading pass are admin-only.
 */
@RestController
@RequestMapping("/api/report-cards")
public class ReportCardController {

	private final ReportCardService cards;
	private final StrategyGateService gates;
	private final GradingJob job;
	private final CurrentUserService currentUser;

	public ReportCardController(ReportCardService cards, StrategyGateService gates, GradingJob job,
			CurrentUserService currentUser) {
		this.cards = cards;
		this.gates = gates;
		this.job = job;
		this.currentUser = currentUser;
	}

	@GetMapping
	public List<Map<String, Object>> cards() {
		return cards.cards();
	}

	@GetMapping("/gates")
	public Map<String, Object> gates() {
		return Map.of("killSwitch", gates.killSwitch(), "gates", gates.all());
	}

	public record Pin(Double multiplier) {
	}

	@PutMapping("/{agent}/pin")
	public void pin(@PathVariable String agent, @RequestBody Pin pin, HttpServletRequest request) {
		currentUser.requireAdmin(request);
		cards.pin(agent, pin.multiplier());
	}

	@DeleteMapping("/{agent}/pin")
	public void unpin(@PathVariable String agent, HttpServletRequest request) {
		currentUser.requireAdmin(request);
		cards.pin(agent, null);
	}

	@PostMapping("/run")
	public void run(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		job.runAll();
	}
}
