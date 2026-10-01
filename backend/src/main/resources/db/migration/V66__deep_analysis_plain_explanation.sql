-- A beginner-friendly rendering of Agent 11's thesis/bull case/bear case, generated on demand (not for
-- every analysis — most verdicts are never read in that depth) and cached here so re-opening the ticker
-- never re-pays the model call. Same "what happened / why it matters / key terms" convention the breaking
-- news carousel already uses (BreakingAlertCurationService), reusing the same frontend renderer.
alter table deep_analysis
    add column plain_explanation    text,
    add column plain_explanation_at timestamptz;
