-- V83__graduation_counts_current_system_only.sql — Agent 5's graduation (shadow → probation → active, and the
-- freeze) is judged only on trades the CURRENT recommendation system opened (2026-09-25 on: conviction
-- scoring + Agents 11-13). On 2026-10-08 the new thesis-decay rule closed five losing old-system bearish legs
-- (opened Jul-Sep) and those outcomes froze Agent 5 — the old coin-flip record judging the new system.
-- Every outcome is still recorded (all-time accuracy is unchanged); only the graduation inputs are scoped.

alter table paper_trades add column counts_for_graduation boolean not null default true;

update paper_trades p set counts_for_graduation = false
where not exists (select 1 from recommendations r where r.id = p.recommendation_id and r.created_at >= '2026-09-25T00:00:00Z');
