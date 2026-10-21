-- PLT-008: per-plan API quota for the tenant token bucket (Valkey, RateLimitFilter). NULL = app.rate-limit.tenant-*
-- (600 requests/min, burst 1,200). Read through master.subscriptions; replicas pick up a change within
-- app.rate-limit.plan-refresh-seconds (30). Plan editing itself is PLT-059's. Additive, master schema (no RLS).
SET lock_timeout = '5s';
ALTER TABLE master.plans ADD COLUMN IF NOT EXISTS api_requests_per_minute integer CHECK (api_requests_per_minute > 0);
ALTER TABLE master.plans ADD COLUMN IF NOT EXISTS api_burst integer CHECK (api_burst > 0);
