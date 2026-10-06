-- V79__seed_scheduled_job_runs.sql — starting points for the scheduled-job run ledger (agent_runs,
-- 'job:<Class>.<method>'), which the scheduler now stamps on every cron completion and the boot
-- catch-up reads (MissedRunCatchUp). Seeded so the first boot catches up the runs the 2026-10-06
-- outage cost: the host was last up at ~05:30 UTC that day (Agent 11's nightly finished 05:04–05:23,
-- Agent 5's 06:00 review never ran) until ~15:20 UTC. Where a job's own output dates it, that wins.

insert into agent_runs (agent_id, last_run_at)
select 'job:' || j, timestamptz '2026-10-06 05:30:00+00'
from unnest(array[
    'com.argus.deepanalysis.DeepAnalysisRunner.nightly',
    'com.argus.deepanalysis.DeepScorecardService.scheduledSnapshot',
    'com.argus.filings.FilingDigestService.refreshUniverse',
    'com.argus.intelligence.BreakingAlertCurationService.cleanupStale',
    'com.argus.intelligence.MacroKeywordLearningService.scheduledReview',
    'com.argus.notification.DigestService.weeklyDigest',
    'com.argus.ops.CleanupScheduler.monthlyCleanup',
    'com.argus.portfolio.PortfolioHistoryService.scheduledCapture',
    'com.argus.recommendation.AdaptiveTuningService.recompute',
    'com.argus.recommendation.LogicReviewService.scheduledReview',
    'com.argus.strategy.StrategyDocImporter.scheduled',
    'com.argus.strategy.StrategyScoreService.scheduled',
    'com.argus.strategy.StrategyUniverseService.scheduled',
    'com.argus.strategy.StrategyValidationService.scheduled',
    'com.argus.watchlist.DiscoveryService.scheduledDiscover'
]) as j
on conflict (agent_id) do nothing;

-- Dated by their own output (an empty table yields no row, and the job is stamped on first sighting).
insert into agent_runs (agent_id, last_run_at)
select 'job:com.argus.briefing.BriefingService.scheduledBriefing', max(generated_at) from briefings having max(generated_at) is not null
on conflict (agent_id) do nothing;
insert into agent_runs (agent_id, last_run_at)
select 'job:com.argus.portfolio.HealthScoreService.scheduledCapture', max(created_at) from health_score having max(created_at) is not null
on conflict (agent_id) do nothing;
insert into agent_runs (agent_id, last_run_at)
select 'job:com.argus.learning.TradeLearner.nightly', max(created_at) from learning_report having max(created_at) is not null
on conflict (agent_id) do nothing;
