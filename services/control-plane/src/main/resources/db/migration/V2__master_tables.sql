-- GENERATED from Core's migrated `master` schema by backend test Plt061ControlPlaneSchemaTest
-- (mvn test -Dtest=Plt061ControlPlaneSchemaTest -Dcontrolplane.write=true). Do not edit by hand.
-- Same names, types, defaults, checks, indexes and triggers as Core. Differences, all on purpose:
--   foreign keys between master tables are DEFERRABLE (the copy job loads tables in any order);
--   foreign keys to Core tables, tenant RLS and grants are not carried (listed at the end).

CREATE OR REPLACE FUNCTION master.operator_audit_log_immutable()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
  RAISE EXCEPTION 'master.operator_audit_log is append-only: % is not permitted', TG_OP;
END $function$;

CREATE OR REPLACE FUNCTION public.set_updated_at()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
  IF coalesce(current_setting('nabd.skip_updated_at', true), '') = 'on' THEN
    RETURN NEW;
  END IF;
  NEW.updated_at = now();
  RETURN NEW;
END;
$function$;

CREATE SEQUENCE master.platform_invoice_seq AS bigint INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1;

CREATE TABLE master.activation_steps (
  tenant_id uuid NOT NULL,
  step text NOT NULL,
  status text NOT NULL,
  first_seen_at timestamp with time zone DEFAULT now() NOT NULL,
  completed_at timestamp with time zone,
  attempts integer DEFAULT 0 NOT NULL,
  last_error_code text,
  nudges_sent integer DEFAULT 0 NOT NULL,
  last_nudged_at timestamp with time zone,
  ticket_id uuid,
  last_event_id bigint DEFAULT 0 NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT activation_steps_pkey PRIMARY KEY (tenant_id, step),
  CONSTRAINT activation_steps_attempts_check CHECK ((attempts >= 0)),
  CONSTRAINT activation_steps_last_error_code_check CHECK ((last_error_code ~ '^[A-Z0-9_]{1,40}$'::text)),
  CONSTRAINT activation_steps_nudges_sent_check CHECK ((nudges_sent >= 0)),
  CONSTRAINT activation_steps_status_check CHECK ((status = ANY (ARRAY['reached'::text, 'done'::text, 'skipped'::text, 'failed'::text]))),
  CONSTRAINT activation_steps_step_check CHECK ((step ~ '^[a-z_]{1,32}$'::text))
);
CREATE INDEX idx_activation_steps_open ON master.activation_steps USING btree (first_seen_at, tenant_id, step) WHERE (completed_at IS NULL);

CREATE TABLE master.deletion_certificates (
  id uuid NOT NULL,
  tenant_id uuid NOT NULL,
  brand_id uuid,
  request_id uuid NOT NULL,
  requested_at timestamp with time zone NOT NULL,
  erased_at timestamp with time zone NOT NULL,
  backup_purge_at timestamp with time zone NOT NULL,
  region text NOT NULL,
  row_counts jsonb NOT NULL,
  signed_doc jsonb NOT NULL,
  signature text NOT NULL,
  kid text NOT NULL,
  doc_sha256 text NOT NULL,
  issued_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT deletion_certificates_pkey PRIMARY KEY (id),
  CONSTRAINT deletion_certificates_request_id_key UNIQUE (request_id),
  CONSTRAINT deletion_certificates_doc_sha256_check CHECK ((doc_sha256 ~ '^[0-9a-f]{64}$'::text))
);

CREATE TABLE master.discount_requests (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  requested_by uuid NOT NULL,
  percent numeric(5,2) NOT NULL,
  reason text NOT NULL,
  status text DEFAULT 'pending'::text NOT NULL,
  reviewed_by uuid,
  reviewed_at timestamp with time zone,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT discount_requests_pkey PRIMARY KEY (id),
  CONSTRAINT discount_requests_percent_check CHECK (((percent > (0)::numeric) AND (percent <= (100)::numeric))),
  CONSTRAINT discount_requests_status_check CHECK ((status = ANY (ARRAY['auto_approved'::text, 'pending'::text, 'approved'::text, 'rejected'::text])))
);
CREATE INDEX idx_discount_requests_tenant ON master.discount_requests USING btree (tenant_id, created_at DESC);

CREATE TABLE master.kill_switches (
  feature_code text NOT NULL,
  active boolean NOT NULL,
  reason text NOT NULL,
  set_by uuid,
  set_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT kill_switches_pkey PRIMARY KEY (feature_code)
);

CREATE TABLE master.login_attempts (
  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
  operator_id uuid,
  email citext NOT NULL,
  ip_address inet NOT NULL,
  succeeded boolean NOT NULL,
  attempted_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT login_attempts_pkey PRIMARY KEY (id)
);
CREATE INDEX idx_master_login_attempts_operator_time ON master.login_attempts USING btree (operator_id, attempted_at DESC) WHERE (operator_id IS NOT NULL);

CREATE TABLE master.operator_audit_log (
  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
  operator_id uuid NOT NULL,
  action text NOT NULL,
  target_tenant_id uuid,
  params jsonb DEFAULT '{}'::jsonb NOT NULL,
  row_count integer NOT NULL,
  reason text,
  ip inet,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT operator_audit_log_pkey PRIMARY KEY (id),
  CONSTRAINT operator_audit_log_row_count_check CHECK ((row_count >= 0))
);
CREATE INDEX idx_operator_audit_log_operator ON master.operator_audit_log USING btree (operator_id, created_at);
CREATE TRIGGER trg_operator_audit_log_no_truncate BEFORE TRUNCATE ON master.operator_audit_log FOR EACH STATEMENT EXECUTE FUNCTION master.operator_audit_log_immutable();
CREATE TRIGGER trg_operator_audit_log_no_update BEFORE DELETE OR UPDATE ON master.operator_audit_log FOR EACH ROW EXECUTE FUNCTION master.operator_audit_log_immutable();

CREATE TABLE master.operator_training_completions (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  operator_id uuid NOT NULL,
  module_id text NOT NULL,
  score integer NOT NULL,
  completed_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT operator_training_completions_pkey PRIMARY KEY (id),
  CONSTRAINT operator_training_completions_module_id_check CHECK ((module_id ~ '^[a-z][a-z0-9-]{1,40}$'::text)),
  CONSTRAINT operator_training_completions_score_check CHECK (((score >= 0) AND (score <= 100)))
);
CREATE UNIQUE INDEX uq_operator_training_completions_day ON master.operator_training_completions USING btree (operator_id, module_id, (((completed_at AT TIME ZONE 'UTC'::text))::date));

CREATE TABLE master.operators (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  name text NOT NULL,
  email citext NOT NULL,
  pin_hash text,
  role text NOT NULL,
  status text DEFAULT 'active'::text NOT NULL,
  mfa_enabled boolean DEFAULT false NOT NULL,
  mfa_secret_enc bytea,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  mfa_last_used_step bigint,
  pin_policy_version smallint DEFAULT 2 NOT NULL,
  mfa_pending_secret_enc bytea,
  mfa_pending_created_at timestamp with time zone,
  CONSTRAINT operators_pkey PRIMARY KEY (id),
  CONSTRAINT operators_email_key UNIQUE (email),
  CONSTRAINT operators_role_check CHECK ((role = ANY (ARRAY['super_admin'::text, 'implementation'::text, 'support_engineer'::text, 'billing'::text, 'sre'::text, 'commercial'::text, 'compliance_dpo'::text]))),
  CONSTRAINT operators_status_check CHECK ((status = ANY (ARRAY['active'::text, 'suspended'::text])))
);
CREATE TRIGGER trg_operators_updated_at BEFORE UPDATE ON master.operators FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE master.plan_entitlements (
  plan_id uuid NOT NULL,
  plan_version integer NOT NULL,
  module_code text NOT NULL,
  limits jsonb DEFAULT '{}'::jsonb NOT NULL,
  CONSTRAINT plan_entitlements_pkey PRIMARY KEY (plan_id, plan_version, module_code)
);

CREATE TABLE master.plan_versions (
  plan_id uuid NOT NULL,
  version integer NOT NULL,
  monthly_price_cents integer NOT NULL,
  seat_limit integer NOT NULL,
  branch_limit integer,
  visits_per_month integer,
  published_at timestamp with time zone DEFAULT now() NOT NULL,
  whatsapp_message_allowance integer,
  CONSTRAINT plan_versions_pkey PRIMARY KEY (plan_id, version),
  CONSTRAINT plan_versions_branch_limit_check CHECK ((branch_limit > 0)),
  CONSTRAINT plan_versions_monthly_price_cents_check CHECK ((monthly_price_cents >= 0)),
  CONSTRAINT plan_versions_seat_limit_check CHECK ((seat_limit > 0)),
  CONSTRAINT plan_versions_version_check CHECK ((version > 0)),
  CONSTRAINT plan_versions_visits_per_month_check CHECK ((visits_per_month > 0)),
  CONSTRAINT plan_versions_whatsapp_message_allowance_check CHECK ((whatsapp_message_allowance > 0))
);

CREATE TABLE master.plans (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  code text NOT NULL,
  name text NOT NULL,
  monthly_price_cents integer NOT NULL,
  currency text NOT NULL,
  seat_limit integer NOT NULL,
  active boolean DEFAULT true NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  otp_hourly_cap integer,
  api_requests_per_minute integer,
  api_burst integer,
  version integer DEFAULT 1 NOT NULL,
  market text,
  pricing_basis text DEFAULT 'per_clinic'::text NOT NULL,
  branch_limit integer,
  visits_per_month integer,
  on_trial_expiry text,
  trial_days integer DEFAULT 14 NOT NULL,
  white_glove_fee_minor bigint,
  CONSTRAINT plans_pkey PRIMARY KEY (id),
  CONSTRAINT plans_code_key UNIQUE (code),
  CONSTRAINT plans_api_burst_check CHECK ((api_burst > 0)),
  CONSTRAINT plans_api_requests_per_minute_check CHECK ((api_requests_per_minute > 0)),
  CONSTRAINT plans_branch_limit_check CHECK ((branch_limit > 0)),
  CONSTRAINT plans_currency_check CHECK ((currency = ANY (ARRAY['INR'::text, 'SAR'::text, 'AED'::text, 'QAR'::text]))),
  CONSTRAINT plans_monthly_price_cents_check CHECK ((monthly_price_cents >= 0)),
  CONSTRAINT plans_otp_hourly_cap_check CHECK ((otp_hourly_cap > 0)),
  CONSTRAINT plans_pricing_basis_check CHECK ((pricing_basis = ANY (ARRAY['per_clinic'::text, 'per_doctor'::text]))),
  CONSTRAINT plans_seat_limit_check CHECK ((seat_limit > 0)),
  CONSTRAINT plans_trial_days_check CHECK (((trial_days >= 1) AND (trial_days <= 90))),
  CONSTRAINT plans_visits_per_month_check CHECK ((visits_per_month > 0)),
  CONSTRAINT plans_white_glove_fee_minor_check CHECK ((white_glove_fee_minor >= 0))
);
CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON master.plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE master.platform_invoices (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  number text NOT NULL,
  kind text NOT NULL,
  period_start date NOT NULL,
  period_end date NOT NULL,
  amount_minor bigint NOT NULL,
  currency text NOT NULL,
  status text DEFAULT 'due'::text NOT NULL,
  change_id uuid,
  issued_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT platform_invoices_pkey PRIMARY KEY (id),
  CONSTRAINT platform_invoices_number_key UNIQUE (number),
  CONSTRAINT platform_invoices_amount_minor_check CHECK ((amount_minor >= 0)),
  CONSTRAINT platform_invoices_currency_check CHECK ((currency = ANY (ARRAY['INR'::text, 'SAR'::text, 'AED'::text, 'QAR'::text]))),
  CONSTRAINT platform_invoices_kind_check CHECK ((kind = ANY (ARRAY['proration'::text, 'renewal'::text, 'white_glove'::text]))),
  CONSTRAINT platform_invoices_status_check CHECK ((status = ANY (ARRAY['due'::text, 'paid'::text, 'void'::text])))
);
CREATE INDEX idx_platform_invoices_tenant ON master.platform_invoices USING btree (tenant_id, issued_at DESC, id DESC);

CREATE TABLE master.provisioning_job_steps (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  job_id uuid NOT NULL,
  step_name text NOT NULL,
  step_order smallint NOT NULL,
  status text DEFAULT 'queued'::text NOT NULL,
  started_at timestamp with time zone,
  completed_at timestamp with time zone,
  error_detail text,
  effect_committed_at timestamp with time zone,
  CONSTRAINT provisioning_job_steps_pkey PRIMARY KEY (id),
  CONSTRAINT provisioning_job_steps_job_id_step_name_key UNIQUE (job_id, step_name),
  CONSTRAINT provisioning_job_steps_status_check CHECK ((status = ANY (ARRAY['queued'::text, 'running'::text, 'done'::text, 'failed'::text, 'rolled_back'::text]))),
  CONSTRAINT provisioning_job_steps_step_name_check CHECK ((step_name = ANY (ARRAY['create_tenant'::text, 'migrate_schema'::text, 'seed_masters'::text, 'provision_whatsapp'::text, 'verify_invite_owner'::text, 'go_live'::text, 'copy_config'::text])))
);
CREATE INDEX idx_provisioning_job_steps_job ON master.provisioning_job_steps USING btree (job_id, step_order);
CREATE INDEX idx_provisioning_job_steps_running ON master.provisioning_job_steps USING btree (started_at) WHERE (status = 'running'::text);

CREATE TABLE master.provisioning_jobs (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  requested_by uuid,
  tenant_slug citext NOT NULL,
  tenant_name text NOT NULL,
  region text NOT NULL,
  owner_email citext NOT NULL,
  owner_name text NOT NULL,
  brand_name text NOT NULL,
  status text DEFAULT 'queued'::text NOT NULL,
  created_tenant_id uuid,
  created_owner_id uuid,
  created_brand_id uuid,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  owner_newly_created boolean DEFAULT false NOT NULL,
  brand_newly_created boolean DEFAULT false NOT NULL,
  path text DEFAULT 'self_serve'::text NOT NULL,
  approved_at timestamp with time zone,
  approved_by uuid,
  owner_mobile text,
  source_tenant_id uuid,
  requested_by_staff uuid,
  planned_tenant_id uuid,
  branch_code text,
  branch_address text,
  timezone text,
  copy_sections text[],
  step_up_id uuid,
  edition text DEFAULT 'clinic'::text NOT NULL,
  CONSTRAINT provisioning_jobs_pkey PRIMARY KEY (id),
  CONSTRAINT provisioning_jobs_step_up_id_key UNIQUE (step_up_id),
  CONSTRAINT provisioning_jobs_edition_check CHECK ((edition = ANY (ARRAY['clinic'::text, 'pharmacy'::text, 'lab'::text]))),
  CONSTRAINT provisioning_jobs_path_check CHECK ((path = ANY (ARRAY['self_serve'::text, 'enterprise'::text]))),
  CONSTRAINT provisioning_jobs_region_check CHECK ((region = ANY (ARRAY['IN'::text, 'KSA'::text, 'SA'::text, 'AE'::text, 'QA'::text]))),
  CONSTRAINT provisioning_jobs_requester_check CHECK (((requested_by IS NOT NULL) OR ((requested_by_staff IS NOT NULL) AND (source_tenant_id IS NOT NULL)))),
  CONSTRAINT provisioning_jobs_status_check CHECK ((status = ANY (ARRAY['queued'::text, 'running'::text, 'done'::text, 'failed'::text, 'rolled_back'::text])))
);
CREATE INDEX idx_provisioning_jobs_requested_by ON master.provisioning_jobs USING btree (requested_by);
CREATE TRIGGER trg_provisioning_jobs_updated_at BEFORE UPDATE ON master.provisioning_jobs FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE master.regulatory_watch_entries (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  source text NOT NULL,
  market text NOT NULL,
  title text NOT NULL,
  impact_level text NOT NULL,
  impact text NOT NULL,
  linked_ref text NOT NULL,
  noticed_on date NOT NULL,
  recorded_at timestamp with time zone DEFAULT now() NOT NULL,
  recorded_by uuid NOT NULL,
  CONSTRAINT regulatory_watch_entries_pkey PRIMARY KEY (id),
  CONSTRAINT regulatory_watch_entries_impact_check CHECK (((length(impact) >= 3) AND (length(impact) <= 2000))),
  CONSTRAINT regulatory_watch_entries_impact_level_check CHECK ((impact_level = ANY (ARRAY['none'::text, 'low'::text, 'medium'::text, 'high'::text]))),
  CONSTRAINT regulatory_watch_entries_linked_ref_check CHECK ((linked_ref ~ '^(T|DEC)-[0-9]{1,6}$'::text)),
  CONSTRAINT regulatory_watch_entries_market_check CHECK ((market ~ '^[A-Z]{2,3}$'::text)),
  CONSTRAINT regulatory_watch_entries_source_check CHECK ((source = ANY (ARRAY['DPDP'::text, 'NCNICC'::text, 'ZATCA'::text, 'TRAI_DLT'::text, 'ABDM'::text, 'NABIDH'::text, 'META'::text, 'OTHER'::text]))),
  CONSTRAINT regulatory_watch_entries_title_check CHECK (((length(title) >= 3) AND (length(title) <= 200)))
);
CREATE INDEX idx_regulatory_watch_noticed ON master.regulatory_watch_entries USING btree (noticed_on DESC);

CREATE TABLE master.sessions (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  operator_id uuid NOT NULL,
  family_id uuid NOT NULL,
  token_hash text NOT NULL,
  device_label text,
  ip_address inet,
  user_agent text,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
  expires_at timestamp with time zone NOT NULL,
  revoked_at timestamp with time zone,
  revoked_reason text,
  family_started_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT sessions_pkey PRIMARY KEY (id),
  CONSTRAINT sessions_token_hash_key UNIQUE (token_hash),
  CONSTRAINT sessions_check CHECK (((revoked_at IS NULL) = (revoked_reason IS NULL))),
  CONSTRAINT sessions_revoked_reason_check CHECK ((revoked_reason = ANY (ARRAY['logout'::text, 'rotated'::text, 'reuse_detected'::text, 'suspended'::text, 'admin_revoke'::text, 'max_age'::text])))
);
CREATE INDEX idx_master_sessions_operator ON master.sessions USING btree (operator_id) WHERE (revoked_at IS NULL);

CREATE TABLE master.subscription_changes (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  from_plan_id uuid,
  to_plan_id uuid NOT NULL,
  to_plan_version integer NOT NULL,
  direction text NOT NULL,
  effective text NOT NULL,
  effective_at timestamp with time zone NOT NULL,
  proration_minor bigint NOT NULL,
  currency text NOT NULL,
  status text NOT NULL,
  requested_by_staff uuid NOT NULL,
  step_up_id uuid NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  applied_at timestamp with time zone,
  CONSTRAINT subscription_changes_pkey PRIMARY KEY (id),
  CONSTRAINT subscription_changes_step_up_id_key UNIQUE (step_up_id),
  CONSTRAINT subscription_changes_currency_check CHECK ((currency = ANY (ARRAY['INR'::text, 'SAR'::text, 'AED'::text, 'QAR'::text]))),
  CONSTRAINT subscription_changes_direction_check CHECK ((direction = ANY (ARRAY['upgrade'::text, 'downgrade'::text]))),
  CONSTRAINT subscription_changes_effective_check CHECK ((effective = ANY (ARRAY['now'::text, 'renewal'::text]))),
  CONSTRAINT subscription_changes_status_check CHECK ((status = ANY (ARRAY['scheduled'::text, 'applied'::text, 'cancelled'::text])))
);
CREATE INDEX idx_subscription_changes_tenant ON master.subscription_changes USING btree (tenant_id, created_at DESC);
CREATE UNIQUE INDEX uq_subscription_changes_scheduled ON master.subscription_changes USING btree (tenant_id) WHERE (status = 'scheduled'::text);

CREATE TABLE master.subscriptions (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  mrr_cents integer NOT NULL,
  currency text NOT NULL,
  renewal_date date NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  plan_version integer DEFAULT 1 NOT NULL,
  trial_ends_at timestamp with time zone,
  status text DEFAULT 'active'::text NOT NULL,
  trial_reminded_days integer,
  billing_period text DEFAULT 'monthly'::text NOT NULL,
  CONSTRAINT subscriptions_pkey PRIMARY KEY (id),
  CONSTRAINT subscriptions_tenant_id_key UNIQUE (tenant_id),
  CONSTRAINT subscriptions_billing_period_check CHECK ((billing_period = ANY (ARRAY['monthly'::text, 'annual'::text]))),
  CONSTRAINT subscriptions_currency_check CHECK ((currency = ANY (ARRAY['INR'::text, 'SAR'::text, 'AED'::text, 'QAR'::text]))),
  CONSTRAINT subscriptions_mrr_cents_check CHECK ((mrr_cents >= 0)),
  CONSTRAINT subscriptions_status_check CHECK ((status = ANY (ARRAY['trialing'::text, 'active'::text, 'past_due'::text, 'cancelled'::text, 'free'::text])))
);
CREATE INDEX idx_subscriptions_renewal ON master.subscriptions USING btree (renewal_date);
CREATE INDEX idx_subscriptions_trial_ends ON master.subscriptions USING btree (trial_ends_at) WHERE (status = 'trialing'::text);
CREATE TRIGGER trg_subscriptions_updated_at BEFORE UPDATE ON master.subscriptions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE master.support_access_grants (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  operator_id uuid NOT NULL,
  reason text NOT NULL,
  granted_at timestamp with time zone DEFAULT now() NOT NULL,
  expires_at timestamp with time zone NOT NULL,
  revoked_at timestamp with time zone,
  ticket_id uuid,
  scope text,
  status text,
  minutes integer,
  requested_at timestamp with time zone,
  decided_by_staff_id uuid,
  decided_at timestamp with time zone,
  decline_note text,
  window_started_at timestamp with time zone,
  window_ends_at timestamp with time zone,
  CONSTRAINT support_access_grants_pkey PRIMARY KEY (id),
  CONSTRAINT support_access_grants_minutes_chk CHECK (((minutes IS NULL) OR ((minutes >= 1) AND (minutes <= 60)))),
  CONSTRAINT support_access_grants_scope_chk CHECK (((scope IS NOT NULL) AND (scope = ANY (ARRAY['config_only'::text, 'redacted_patient_view'::text])))),
  CONSTRAINT support_access_grants_status_chk CHECK (((status IS NOT NULL) AND (status = ANY (ARRAY['requested'::text, 'approved'::text, 'declined'::text, 'active'::text, 'expired'::text, 'revoked'::text])))),
  CONSTRAINT support_access_grants_window_max_60_min CHECK (((window_ends_at IS NULL) OR (window_started_at IS NULL) OR ((window_ends_at - window_started_at) <= '01:00:00'::interval)))
);
CREATE INDEX idx_support_access_due ON master.support_access_grants USING btree (expires_at) WHERE (status = ANY (ARRAY['requested'::text, 'active'::text]));
CREATE INDEX idx_support_access_operator ON master.support_access_grants USING btree (operator_id);
CREATE INDEX idx_support_access_tenant ON master.support_access_grants USING btree (tenant_id);
CREATE UNIQUE INDEX uq_support_access_one_open_request ON master.support_access_grants USING btree (tenant_id, ticket_id, operator_id) WHERE (status = 'requested'::text);

CREATE TABLE master.support_tickets (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  source text NOT NULL,
  raised_by_staff_id uuid,
  raised_by_name text NOT NULL,
  raised_by_email citext,
  raised_by_role text NOT NULL,
  subject text NOT NULL,
  description text NOT NULL,
  priority text DEFAULT 'normal'::text NOT NULL,
  status text DEFAULT 'open'::text NOT NULL,
  sla_due_at timestamp with time zone NOT NULL,
  resolved_at timestamp with time zone,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  body_redacted boolean DEFAULT false NOT NULL,
  redaction_count integer DEFAULT 0 NOT NULL,
  CONSTRAINT support_tickets_pkey PRIMARY KEY (id),
  CONSTRAINT support_tickets_check CHECK (((source = 'staff'::text) OR (raised_by_staff_id IS NULL))),
  CONSTRAINT support_tickets_priority_check CHECK ((priority = ANY (ARRAY['low'::text, 'normal'::text, 'high'::text, 'urgent'::text]))),
  CONSTRAINT support_tickets_source_check CHECK ((source = ANY (ARRAY['staff'::text, 'system'::text]))),
  CONSTRAINT support_tickets_status_check CHECK ((status = ANY (ARRAY['open'::text, 'in_progress'::text, 'resolved'::text, 'closed'::text])))
);
CREATE INDEX idx_support_tickets_open_sla ON master.support_tickets USING btree (sla_due_at) WHERE (status = ANY (ARRAY['open'::text, 'in_progress'::text]));
CREATE INDEX idx_support_tickets_tenant ON master.support_tickets USING btree (tenant_id);
CREATE TRIGGER trg_support_tickets_updated_at BEFORE UPDATE ON master.support_tickets FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE master.tenant_feature_overrides (
  tenant_id uuid NOT NULL,
  feature_code text NOT NULL,
  state text NOT NULL,
  reason text NOT NULL,
  set_by uuid,
  set_at timestamp with time zone DEFAULT now() NOT NULL,
  expires_at timestamp with time zone,
  CONSTRAINT tenant_feature_overrides_pkey PRIMARY KEY (tenant_id, feature_code),
  CONSTRAINT tenant_feature_overrides_state_check CHECK ((state = ANY (ARRAY['on'::text, 'off'::text, 'kill'::text])))
);

CREATE TABLE master.trial_signups (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  mobile_hash text NOT NULL,
  ip_hash text NOT NULL,
  market text NOT NULL,
  region text NOT NULL,
  clinic_name text NOT NULL,
  specialty text NOT NULL,
  otp_hash text,
  otp_attempts integer DEFAULT 0 NOT NULL,
  status text DEFAULT 'otp_sent'::text NOT NULL,
  job_id uuid,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT trial_signups_pkey PRIMARY KEY (id),
  CONSTRAINT trial_signups_status_check CHECK ((status = ANY (ARRAY['otp_sent'::text, 'verified'::text, 'provisioning'::text, 'ready'::text, 'expired'::text])))
);
CREATE INDEX idx_trial_signups_ip ON master.trial_signups USING btree (ip_hash, created_at);
CREATE INDEX idx_trial_signups_mobile ON master.trial_signups USING btree (mobile_hash, created_at);

CREATE TABLE master.white_glove_engagements (
  id uuid DEFAULT gen_random_uuid() NOT NULL,
  tenant_id uuid NOT NULL,
  status text DEFAULT 'requested'::text NOT NULL,
  fee_minor bigint NOT NULL,
  currency text NOT NULL,
  fee_status text NOT NULL,
  waiver_reason text,
  invoice_id uuid,
  preferred_slots timestamp with time zone[] NOT NULL,
  notes text,
  slot_at timestamp with time zone,
  operator_id uuid,
  ticket_id uuid,
  covered_steps text[],
  requested_by_staff uuid NOT NULL,
  step_up_id uuid NOT NULL,
  created_at timestamp with time zone DEFAULT now() NOT NULL,
  updated_at timestamp with time zone DEFAULT now() NOT NULL,
  closed_at timestamp with time zone,
  CONSTRAINT white_glove_engagements_pkey PRIMARY KEY (id),
  CONSTRAINT white_glove_engagements_step_up_id_key UNIQUE (step_up_id),
  CONSTRAINT white_glove_engagements_check CHECK (((fee_status = 'waived'::text) = (waiver_reason IS NOT NULL))),
  CONSTRAINT white_glove_engagements_currency_check CHECK ((currency = ANY (ARRAY['INR'::text, 'SAR'::text, 'AED'::text, 'QAR'::text]))),
  CONSTRAINT white_glove_engagements_fee_minor_check CHECK ((fee_minor >= 0)),
  CONSTRAINT white_glove_engagements_fee_status_check CHECK ((fee_status = ANY (ARRAY['due'::text, 'paid'::text, 'waived'::text]))),
  CONSTRAINT white_glove_engagements_notes_check CHECK ((length(notes) <= 500)),
  CONSTRAINT white_glove_engagements_preferred_slots_check CHECK (((cardinality(preferred_slots) >= 1) AND (cardinality(preferred_slots) <= 3))),
  CONSTRAINT white_glove_engagements_status_check CHECK ((status = ANY (ARRAY['requested'::text, 'scheduled'::text, 'in_progress'::text, 'done'::text, 'cancelled'::text]))),
  CONSTRAINT white_glove_engagements_waiver_reason_check CHECK ((waiver_reason = 'annual_plan'::text))
);
CREATE INDEX idx_white_glove_queue ON master.white_glove_engagements USING btree (status, created_at);
CREATE UNIQUE INDEX uq_white_glove_open ON master.white_glove_engagements USING btree (tenant_id) WHERE (status = ANY (ARRAY['requested'::text, 'scheduled'::text, 'in_progress'::text]));

-- foreign keys between master tables
ALTER TABLE master.discount_requests ADD CONSTRAINT discount_requests_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.discount_requests ADD CONSTRAINT discount_requests_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.login_attempts ADD CONSTRAINT login_attempts_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.operator_audit_log ADD CONSTRAINT operator_audit_log_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.operator_training_completions ADD CONSTRAINT operator_training_completions_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.plan_entitlements ADD CONSTRAINT plan_entitlements_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES master.plans(id) DEFERRABLE;
ALTER TABLE master.plan_entitlements ADD CONSTRAINT plan_entitlements_plan_id_plan_version_fkey FOREIGN KEY (plan_id, plan_version) REFERENCES master.plan_versions(plan_id, version) DEFERRABLE;
ALTER TABLE master.plan_versions ADD CONSTRAINT plan_versions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES master.plans(id) DEFERRABLE;
ALTER TABLE master.platform_invoices ADD CONSTRAINT platform_invoices_change_id_fkey FOREIGN KEY (change_id) REFERENCES master.subscription_changes(id) DEFERRABLE;
ALTER TABLE master.provisioning_job_steps ADD CONSTRAINT provisioning_job_steps_job_id_fkey FOREIGN KEY (job_id) REFERENCES master.provisioning_jobs(id) DEFERRABLE;
ALTER TABLE master.provisioning_jobs ADD CONSTRAINT provisioning_jobs_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.provisioning_jobs ADD CONSTRAINT provisioning_jobs_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.regulatory_watch_entries ADD CONSTRAINT regulatory_watch_entries_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.sessions ADD CONSTRAINT sessions_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.subscription_changes ADD CONSTRAINT subscription_changes_from_plan_id_fkey FOREIGN KEY (from_plan_id) REFERENCES master.plans(id) DEFERRABLE;
ALTER TABLE master.subscription_changes ADD CONSTRAINT subscription_changes_to_plan_id_fkey FOREIGN KEY (to_plan_id) REFERENCES master.plans(id) DEFERRABLE;
ALTER TABLE master.subscriptions ADD CONSTRAINT subscriptions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES master.plans(id) DEFERRABLE;
ALTER TABLE master.support_access_grants ADD CONSTRAINT support_access_grants_operator_id_fkey FOREIGN KEY (operator_id) REFERENCES master.operators(id) DEFERRABLE;
ALTER TABLE master.support_access_grants ADD CONSTRAINT support_access_grants_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES master.support_tickets(id) DEFERRABLE;
ALTER TABLE master.white_glove_engagements ADD CONSTRAINT white_glove_engagements_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES master.platform_invoices(id) DEFERRABLE;

-- Not carried from Core:
--   master.activation_steps activation_steps_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.activation_steps: tenant row level security
--   master.deletion_certificates deletion_certificates_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.deletion_certificates: tenant row level security
--   master.discount_requests discount_requests_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.platform_invoices platform_invoices_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.platform_invoices: tenant row level security
--   master.provisioning_jobs provisioning_jobs_created_brand_id_fkey: FOREIGN KEY (created_brand_id) REFERENCES brands(id)
--   master.provisioning_jobs provisioning_jobs_created_owner_id_fkey: FOREIGN KEY (created_owner_id) REFERENCES owners(id)
--   master.provisioning_jobs provisioning_jobs_created_tenant_id_fkey: FOREIGN KEY (created_tenant_id) REFERENCES tenants(id)
--   master.provisioning_jobs provisioning_jobs_source_tenant_id_fkey: FOREIGN KEY (source_tenant_id) REFERENCES tenants(id)
--   master.subscription_changes subscription_changes_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.subscription_changes: tenant row level security
--   master.subscriptions subscriptions_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.support_access_grants support_access_grants_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.support_tickets support_tickets_raised_by_staff_id_fkey: FOREIGN KEY (raised_by_staff_id) REFERENCES staff(id)
--   master.support_tickets support_tickets_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.tenant_feature_overrides tenant_feature_overrides_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.white_glove_engagements white_glove_engagements_tenant_id_fkey: FOREIGN KEY (tenant_id) REFERENCES tenants(id)
--   master.white_glove_engagements: tenant row level security
