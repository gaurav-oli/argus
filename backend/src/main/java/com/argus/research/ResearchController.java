package com.argus.research;

import com.argus.common.NotFoundException;
import com.argus.research.ResearchAgentService.ResearchJobView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 9 — on-demand research endpoints, session-gated under {@code /api/research} like every
 * {@code /api/*} path. A job runs in the background once started; {@code GET /jobs/{id}} is both the
 * refresh-recovery path (a page reload re-hydrates from here) and a fallback for a client that never
 * connects the WebSocket push. */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

	private final ResearchAgentService agent;
	private final ResearchJobRepository jobs;

	public ResearchController(ResearchAgentService agent, ResearchJobRepository jobs) {
		this.agent = agent;
		this.jobs = jobs;
	}

	/** Start a research pass on {@code ticker}; returns immediately with the job in PLANNING state. */
	@PostMapping("/jobs")
	public ResearchJobView start(@RequestBody StartRequest body) {
		return ResearchJobView.from(agent.startJob(body.ticker()));
	}

	/** Recent jobs, most recent first. */
	@GetMapping("/jobs")
	public List<ResearchJobView> list() {
		return jobs.findTop20ByOrderByCreatedAtDesc().stream().map(ResearchJobView::from).toList();
	}

	/** Poll/hydrate one job — the refresh-recovery path (a WebSocket push is best-effort, this isn't). */
	@GetMapping("/jobs/{id}")
	public ResearchJobView get(@PathVariable Long id) {
		return jobs.findById(id).map(ResearchJobView::from)
				.orElseThrow(() -> new NotFoundException("Research job", String.valueOf(id)));
	}

	public record StartRequest(String ticker) {
	}
}
