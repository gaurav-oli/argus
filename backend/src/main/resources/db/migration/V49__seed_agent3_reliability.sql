-- Re-admit Agent 3 (internet-buzz) into the self-tuning loop at the configured trust floor, instead
-- of the naive default an unseen agent would otherwise get (weight_multiplier defaults to 1.0 = full
-- trust). From here on, AdaptiveTuningService's nightly recompute and LogicReviewService's
-- backtest-gated factor refinement own this value exactly like every other agent's.
insert into agent_reliability (agent, sample_size, hit_rate, weight_multiplier)
values ('agent-3-internet', 0, null, 0.25)
on conflict (agent) do nothing;
