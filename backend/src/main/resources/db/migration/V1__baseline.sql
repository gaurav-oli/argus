-- V1__baseline.sql — Argus data layer baseline (Story 1.2).
--
-- Establishes the pgvector extension used for semantic search / embeddings.
-- No domain tables yet — each feature story brings its own forward-only
-- migration (V2, V3, ...). Never edit an already-applied migration.

CREATE EXTENSION IF NOT EXISTS vector;
