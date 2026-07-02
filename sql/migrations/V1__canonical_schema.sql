create table if not exists network_players (
    player_id uuid primary key,
    last_username varchar(16) not null,
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);

create table if not exists network_ranks (
    rank_id bigserial primary key,
    rank_key varchar(64) not null unique,
    display_name varchar(64) not null,
    priority integer not null default 0,
    created_at timestamptz not null default now()
);

create table if not exists network_player_ranks (
    player_id uuid not null references network_players(player_id) on delete cascade,
    rank_id bigint not null references network_ranks(rank_id) on delete cascade,
    granted_at timestamptz not null default now(),
    expires_at timestamptz null,
    primary key (player_id, rank_id)
);

create table if not exists network_friendships (
    player_id uuid not null references network_players(player_id) on delete cascade,
    friend_id uuid not null references network_players(player_id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (player_id, friend_id),
    check (player_id <> friend_id)
);

create table if not exists network_purchases (
    purchase_id bigserial primary key,
    external_order_id varchar(128) not null unique,
    player_id uuid not null references network_players(player_id) on delete cascade,
    product_key varchar(128) not null,
    status varchar(32) not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    fulfilled_at timestamptz null
);

create table if not exists network_player_sessions (
    session_id bigserial primary key,
    player_id uuid not null references network_players(player_id) on delete cascade,
    server_id varchar(64) not null,
    joined_at timestamptz not null default now(),
    left_at timestamptz null
);

create index if not exists idx_player_sessions_player_id on network_player_sessions(player_id);
create index if not exists idx_player_sessions_joined_at on network_player_sessions(joined_at);

create table if not exists network_server_registry (
    server_id varchar(64) primary key,
    role varchar(32) not null,
    last_heartbeat_at timestamptz not null default now(),
    online_players integer not null default 0,
    max_players integer not null default 100,
    joinable boolean not null default true,
    maintenance boolean not null default false
);


-- Add display columns to network_ranks
alter table network_ranks add column if not exists prefix varchar(64) not null default '';
alter table network_ranks add column if not exists chat_color varchar(32) not null default 'white';

-- Seed default rank ladder (idempotent)
insert into network_ranks (rank_key, display_name, priority, prefix, chat_color)
values
    ('default', 'Player',  0,    '',          'white'),
    ('vip',     'VIP',     100,  '[VIP] ',    'green'),
    ('vip+',    'VIP+',    200,  '[VIP+] ',   'bright_green'),
    ('mvp',     'MVP',     300,  '[MVP] ',    'aqua'),
    ('mvp+',    'MVP+',    400,  '[MVP+] ',   'light_purple'),
    ('staff',   'Staff',   500,  '[Staff] ',  'yellow'),
    ('admin',   'Admin',   900,  '[Admin] ',  'red'),
    ('owner',   'Owner',   1000, '[Owner] ',  'gold')
on conflict (rank_key) do update
    set display_name = excluded.display_name,
        priority     = excluded.priority,
        prefix       = excluded.prefix,
        chat_color   = excluded.chat_color;

-- Faster lookup: active rank assignments per player
create index if not exists idx_player_ranks_player_expires
    on network_player_ranks (player_id, expires_at);


CREATE TABLE IF NOT EXISTS network_player_admin_overrides (
    player_id   UUID PRIMARY KEY REFERENCES network_players(player_id) ON DELETE CASCADE,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  VARCHAR(64) NOT NULL DEFAULT 'console'
);


CREATE TABLE IF NOT EXISTS network_rank_audit (
    audit_id      BIGSERIAL PRIMARY KEY,
    player_id     UUID NOT NULL REFERENCES network_players(player_id) ON DELETE CASCADE,
    actor         VARCHAR(64) NOT NULL,
    action        VARCHAR(32) NOT NULL,
    old_rank_key  VARCHAR(32),
    new_rank_key  VARCHAR(32),
    notes         VARCHAR(255) NOT NULL DEFAULT '',
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rank_audit_player_time
    ON network_rank_audit(player_id, occurred_at DESC);


-- Suite B: Daily SMP Objective Rotation
-- Tracks per-player progress toward the 3 daily objectives (one per track).
-- Reset is logical: rows are keyed by objective_date so history is preserved for telemetry.

CREATE TABLE IF NOT EXISTS smp_daily_objective_progress (
    player_uuid    UUID         NOT NULL,
    objective_date DATE         NOT NULL,
    objective_id   TEXT         NOT NULL,
    progress       INT          NOT NULL DEFAULT 0,
    completed      BOOLEAN      NOT NULL DEFAULT FALSE,
    claimed_at     TIMESTAMPTZ,
    PRIMARY KEY (player_uuid, objective_date, objective_id)
);

-- Distribution query: how many completions per objective per day
CREATE INDEX IF NOT EXISTS idx_sdop_date_obj
    ON smp_daily_objective_progress (objective_date, objective_id)
    WHERE completed = TRUE;


-- Suite C: Weekly Server Event Framework
-- Two tables:
--   smp_events           - one row per event instance (lifecycle tracking)
--   smp_event_scores     - one row per participating player per event

CREATE TABLE IF NOT EXISTS smp_events (
    event_id        TEXT         PRIMARY KEY,
    event_type      TEXT         NOT NULL,
    display_name    TEXT         NOT NULL,
    state           TEXT         NOT NULL DEFAULT 'SCHEDULED',
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    budget_cap      BIGINT       NOT NULL DEFAULT 0,
    payout_json     JSONB,
    cancelled_by    TEXT,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS smp_event_scores (
    event_id        TEXT         NOT NULL REFERENCES smp_events(event_id),
    player_uuid     UUID         NOT NULL,
    player_name     TEXT         NOT NULL,
    score           BIGINT       NOT NULL DEFAULT 0,
    session_seconds INT          NOT NULL DEFAULT 0,
    rewarded_coins  BIGINT,
    placement       INT,
    PRIMARY KEY (event_id, player_uuid)
);

CREATE INDEX IF NOT EXISTS idx_ses_event_score
    ON smp_event_scores (event_id, score DESC)
    WHERE rewarded_coins IS NULL;


-- Suite D foundation: explicit backend lifecycle contract for orchestration-aware routing.
--
-- Why:
--   Heartbeats alone only describe liveness. Production routing needs an explicit
--   lifecycle state machine so orchestrators can drain/reset/hold servers safely.
--
-- Notes:
--   - lifecycle_state = current effective runtime state
--   - desired_lifecycle_state = target state requested by orchestrator/operator
--   - orchestration_generation supports monotonic state transitions by external controllers

ALTER TABLE network_server_registry
    ADD COLUMN IF NOT EXISTS lifecycle_state varchar(32) NOT NULL DEFAULT 'READY',
    ADD COLUMN IF NOT EXISTS desired_lifecycle_state varchar(32) NULL,
    ADD COLUMN IF NOT EXISTS lifecycle_reason varchar(255) NULL,
    ADD COLUMN IF NOT EXISTS orchestrator_source varchar(64) NULL,
    ADD COLUMN IF NOT EXISTS orchestration_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lifecycle_updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_network_server_registry_lifecycle
    ON network_server_registry (lifecycle_state);

CREATE INDEX IF NOT EXISTS idx_network_server_registry_desired_lifecycle
    ON network_server_registry (desired_lifecycle_state);

CREATE TABLE IF NOT EXISTS network_server_lifecycle_audit (
    audit_id bigserial PRIMARY KEY,
    server_id varchar(64) NOT NULL,
    previous_state varchar(32) NULL,
    new_state varchar(32) NOT NULL,
    desired_state varchar(32) NULL,
    reason varchar(255) NULL,
    source varchar(64) NULL,
    generation bigint NOT NULL DEFAULT 0,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_network_server_lifecycle_audit_server_time
    ON network_server_lifecycle_audit (server_id, changed_at DESC);


-- Suite C foundation: world preset promotion lifecycle.
--
-- This schema allows presets to be registered, validated, promoted, and rolled
-- back with full auditability.

CREATE TABLE IF NOT EXISTS network_world_presets (
    preset_id varchar(64) PRIMARY KEY,
    version integer NOT NULL DEFAULT 1,
    status varchar(32) NOT NULL DEFAULT 'DRAFT', -- DRAFT|VALIDATED|ACTIVE|DEPRECATED
    description varchar(255) NULL,
    checksum_sha256 varchar(64) NULL,
    world_count integer NOT NULL DEFAULT 1,
    created_by varchar(64) NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_network_world_presets_status
    ON network_world_presets (status);

CREATE TABLE IF NOT EXISTS network_world_preset_promotions (
    promotion_id bigserial PRIMARY KEY,
    preset_id varchar(64) NOT NULL,
    from_status varchar(32) NULL,
    to_status varchar(32) NOT NULL,
    server_scope varchar(64) NULL, -- null=global or specific cluster/mode
    reason varchar(255) NULL,
    promoted_by varchar(64) NULL,
    promoted_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (preset_id) REFERENCES network_world_presets (preset_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_network_world_preset_promotions_preset_time
    ON network_world_preset_promotions (preset_id, promoted_at DESC);


-- V10: Coin transaction log
-- Stores an append-only ledger of every coin mutation for auditing and player history.

CREATE TABLE IF NOT EXISTS network_coin_transactions (
    id            BIGSERIAL PRIMARY KEY,
    player_uuid   UUID        NOT NULL,
    player_name   TEXT        NOT NULL,
    delta         BIGINT      NOT NULL,   -- positive = credit, negative = debit
    balance_after BIGINT      NOT NULL,
    reason        TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_coin_tx_player ON network_coin_transactions (player_uuid, occurred_at DESC);


-- V11: Persistent NPC registry for creator-authored NPCs across all servers
CREATE TABLE IF NOT EXISTS network_npcs (
    id            TEXT        NOT NULL,
    server_id     TEXT        NOT NULL,
    display_name  TEXT        NOT NULL DEFAULT '<gold><bold>NPC</bold>',
    entity_type   TEXT        NOT NULL DEFAULT 'VILLAGER',
    world_name    TEXT        NOT NULL DEFAULT 'world',
    x             DOUBLE PRECISION NOT NULL,
    y             DOUBLE PRECISION NOT NULL,
    z             DOUBLE PRECISION NOT NULL,
    yaw           REAL        NOT NULL DEFAULT 0,
    pitch         REAL        NOT NULL DEFAULT 0,
    action_type   TEXT        NOT NULL DEFAULT 'NONE',
    action_data   TEXT        NOT NULL DEFAULT '',
    created_by    TEXT        NOT NULL DEFAULT 'system',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, server_id)
);


CREATE TABLE IF NOT EXISTS survival_claims (
    claim_id SERIAL PRIMARY KEY,
    player_uuid UUID NOT NULL,
    claim_name VARCHAR(64) NOT NULL,
    x1 INTEGER NOT NULL,
    z1 INTEGER NOT NULL,
    x2 INTEGER NOT NULL,
    z2 INTEGER NOT NULL,
    members JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ DEFAULT NOW() + INTERVAL '90 days'
);
CREATE INDEX IF NOT EXISTS idx_survival_claims_player ON survival_claims(player_uuid);
CREATE INDEX IF NOT EXISTS idx_survival_claims_expire ON survival_claims(expires_at);


CREATE TABLE IF NOT EXISTS survival_level (
    player_uuid UUID PRIMARY KEY,
    current_level INTEGER DEFAULT 1,
    current_xp INTEGER DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS survival_player_progress (
    player_uuid UUID PRIMARY KEY,
    completed_quests JSONB DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS survival_teams (
    team_id SERIAL PRIMARY KEY,
    team_name VARCHAR(64) NOT NULL,
    leader_uuid UUID NOT NULL,
    members JSONB DEFAULT '[]'::jsonb,
    wealth BIGINT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_survival_teams_leader ON survival_teams(leader_uuid);


CREATE TABLE IF NOT EXISTS survival_daily_rewards (
    player_uuid UUID PRIMARY KEY,
    last_claim TIMESTAMPTZ,
    claim_streak INTEGER DEFAULT 0
);


CREATE TABLE IF NOT EXISTS survival_boss_encounters (
    encounter_id SERIAL PRIMARY KEY,
    boss_type VARCHAR(32) NOT NULL,
    participants JSONB DEFAULT '[]'::jsonb,
    winners JSONB DEFAULT '[]'::jsonb,
    loot_table JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ DEFAULT NOW()
);


CREATE TABLE IF NOT EXISTS survival_afk_tracking (
    player_uuid UUID PRIMARY KEY,
    last_activity TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS survival_afk_sessions (
    session_id BIGSERIAL PRIMARY KEY,
    player_uuid UUID NOT NULL,
    duration_ms BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_afk_tracking_player ON survival_afk_tracking(player_uuid);


CREATE TABLE IF NOT EXISTS survival_trading_hall (
    listing_id BIGSERIAL PRIMARY KEY,
    seller_uuid UUID NOT NULL,
    item TEXT NOT NULL,
    qty INTEGER NOT NULL,
    price BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trading_hall_seller ON survival_trading_hall(seller_uuid);
CREATE INDEX IF NOT EXISTS idx_trading_hall_item ON survival_trading_hall(item);


CREATE TABLE IF NOT EXISTS survival_seasonal_events (
    event_id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    rewards JSONB
);

CREATE TABLE IF NOT EXISTS survival_event_completions (
    completion_id BIGSERIAL PRIMARY KEY,
    player_uuid UUID NOT NULL,
    event_name TEXT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seasonal_events_active ON survival_seasonal_events(starts_at, ends_at);
CREATE INDEX IF NOT EXISTS idx_event_completions_player ON survival_event_completions(player_uuid, event_name);


CREATE TABLE IF NOT EXISTS survival_raids (
    raid_id BIGSERIAL PRIMARY KEY,
    team_id INTEGER NOT NULL,
    raid_type TEXT NOT NULL,
    participants JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    boss_phase INTEGER DEFAULT 1,
    wave_count INTEGER DEFAULT 0,
    total_reward BIGINT DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_raids_team ON survival_raids(team_id);
CREATE INDEX IF NOT EXISTS idx_raids_status ON survival_raids(status);


CREATE TABLE IF NOT EXISTS survival_rent_payments (
    payment_id BIGSERIAL PRIMARY KEY,
    claim_id INTEGER NOT NULL,
    amount BIGINT NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rent_payments_claim ON survival_rent_payments(claim_id);


CREATE TABLE IF NOT EXISTS survival_global_leaderboard (
    snapshot_id BIGSERIAL PRIMARY KEY,
    server_name TEXT NOT NULL,
    snapshot_data JSONB NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_global_leaderboard_server ON survival_global_leaderboard(server_name, timestamp DESC);


CREATE TABLE IF NOT EXISTS survival_seasons (
    season_id BIGSERIAL PRIMARY KEY,
    season_num INTEGER NOT NULL UNIQUE,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE IF NOT EXISTS survival_season_archives (
    archive_id BIGSERIAL PRIMARY KEY,
    season_num INTEGER NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_seasons_active ON survival_seasons(status);


CREATE TABLE IF NOT EXISTS survival_auction_house_listings (
    listing_id            BIGSERIAL PRIMARY KEY,
    seller_uuid           UUID        NOT NULL,
    seller_name           TEXT        NOT NULL,
    buyer_uuid            UUID,
    buyer_name            TEXT,
    material_key          TEXT        NOT NULL,
    item_amount           INTEGER     NOT NULL CHECK (item_amount > 0),
    item_name             TEXT        NOT NULL,
    serialized_item       TEXT        NOT NULL,
    item_fingerprint      TEXT        NOT NULL,
    unit_price            BIGINT      NOT NULL CHECK (unit_price > 0),
    total_price           BIGINT      NOT NULL CHECK (total_price > 0),
    deposit_paid          BIGINT      NOT NULL DEFAULT 0 CHECK (deposit_paid >= 0),
    sale_tax_paid         BIGINT      NOT NULL DEFAULT 0 CHECK (sale_tax_paid >= 0),
    duration_seconds      INTEGER     NOT NULL CHECK (duration_seconds > 0),
    state                 TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    seller_claimed_at     TIMESTAMPTZ,
    buyer_claimed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_auction_active_created
    ON survival_auction_house_listings (state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_seller_state
    ON survival_auction_house_listings (seller_uuid, state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_material_state
    ON survival_auction_house_listings (material_key, state, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_auction_expires
    ON survival_auction_house_listings (state, expires_at);

CREATE TABLE IF NOT EXISTS survival_auction_house_claims (
    claim_id              BIGSERIAL PRIMARY KEY,
    listing_id            BIGINT      REFERENCES survival_auction_house_listings(listing_id) ON DELETE SET NULL,
    owner_uuid            UUID        NOT NULL,
    owner_name            TEXT        NOT NULL,
    claim_type            TEXT        NOT NULL,
    serialized_item       TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_auction_claim_owner
    ON survival_auction_house_claims (owner_uuid, claimed_at, created_at DESC);

ALTER TABLE survival_auction_house_listings
    ADD COLUMN IF NOT EXISTS source_slot INTEGER;

ALTER TABLE survival_auction_house_claims
    ADD COLUMN IF NOT EXISTS delivery_started_at TIMESTAMPTZ;

ALTER TABLE survival_auction_house_claims
    ADD COLUMN IF NOT EXISTS delivery_slot INTEGER;

CREATE INDEX IF NOT EXISTS idx_auction_claim_owner_delivery
    ON survival_auction_house_claims (owner_uuid, claimed_at, delivery_started_at, created_at DESC);

insert into network_ranks (rank_key, display_name, priority, prefix, chat_color)
values
    ('legend', 'Legend', 450, '[Legend] ', 'gold')
on conflict (rank_key) do update
    set display_name = excluded.display_name,
        priority     = excluded.priority,
        prefix       = excluded.prefix,
        chat_color   = excluded.chat_color;

create table if not exists network_friend_requests (
    requester_id uuid not null,
    target_id uuid not null,
    requested_at timestamptz not null default now(),
    expires_at timestamptz not null,
    primary key (requester_id, target_id)
);

create index if not exists idx_friend_requests_target
    on network_friend_requests (target_id, requested_at desc);

do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = current_schema()
          and table_name = 'network_friendships'
          and column_name = 'player_id'
    ) and exists (
        select 1
        from information_schema.columns
        where table_schema = current_schema()
          and table_name = 'network_friendships'
          and column_name = 'friend_id'
    ) and not exists (
        select 1
        from information_schema.columns
        where table_schema = current_schema()
          and table_name = 'network_friendships'
          and column_name = 'player_a'
    ) and not exists (
        select 1
        from information_schema.columns
        where table_schema = current_schema()
          and table_name = 'network_friendships'
          and column_name = 'player_b'
    ) then
        alter table network_friendships rename column player_id to player_a;
        alter table network_friendships rename column friend_id to player_b;
    end if;
end $$;

create table if not exists network_friendships (
    player_a uuid not null,
    player_b uuid not null,
    created_at timestamptz not null default now(),
    created_by uuid,
    primary key (player_a, player_b)
);

alter table network_friendships
    add column if not exists created_by uuid;

create index if not exists idx_friendships_player_a on network_friendships (player_a);
create index if not exists idx_friendships_player_b on network_friendships (player_b);

create table if not exists network_parties (
    party_id uuid primary key,
    leader_id uuid not null,
    follow_leader boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists network_party_members (
    party_id uuid not null references network_parties(party_id) on delete cascade,
    member_id uuid not null,
    joined_at timestamptz not null default now(),
    role text not null default 'MEMBER',
    primary key (party_id, member_id)
);

create index if not exists idx_party_members_member on network_party_members (member_id);

create table if not exists network_party_queue_groups (
    group_id uuid primary key,
    party_id uuid,
    leader_id uuid not null,
    queue_key text not null,
    destination_key text not null,
    member_count int not null,
    state text not null default 'QUEUED',
    queued_at timestamptz not null default now(),
    dequeued_at timestamptz
);

create index if not exists idx_party_queue_groups_state on network_party_queue_groups (state, queued_at);


-- V29: Network-wide objectives (gems currency) + per-gamemode isolation for SMP objectives
-- ──────────────────────────────────────────────────────────────────────────────────────────
-- Part 1: Extend smp_daily_objective_progress with a gamemode scope column so that
--         multiple game-mode plugins can use the same table without key collisions.
--         The existing primary key is (player_uuid, objective_date, objective_id).
--         We add gamemode with DEFAULT 'SURVIVAL' so existing rows remain valid,
--         then migrate the PK to include it.

ALTER TABLE smp_daily_objective_progress
    ADD COLUMN IF NOT EXISTS gamemode TEXT NOT NULL DEFAULT 'SURVIVAL';

-- Drop the old PK and replace with one that includes gamemode.
-- This is safe: existing rows all get 'SURVIVAL', and the combination
-- (player_uuid, objective_date, objective_id, 'SURVIVAL') is still unique.
ALTER TABLE smp_daily_objective_progress
    DROP CONSTRAINT IF EXISTS smp_daily_objective_progress_pkey;

ALTER TABLE smp_daily_objective_progress
    ADD PRIMARY KEY (player_uuid, objective_date, objective_id, gamemode);

-- Also update the analytics index to cover gamemode.
DROP INDEX IF EXISTS idx_sdop_date_obj;
CREATE INDEX IF NOT EXISTS idx_sdop_date_obj_gm
    ON smp_daily_objective_progress (objective_date, objective_id, gamemode)
    WHERE completed = TRUE;

-- ──────────────────────────────────────────────────────────────────────────────────────────
-- Part 2: Network-wide objective progress (daily / weekly / monthly).
--         Key: (player_uuid, objective_id, period_key).
--         period_key is a string like "2026-05-18", "2026-W20", or "2026-05".
--         cadence stores the enum name for audit queries.
--         Progress is capped at the target on the application side; completed/claimed_at
--         are set atomically by markCompletedAsync.

CREATE TABLE IF NOT EXISTS network_objective_progress (
    player_uuid   UUID         NOT NULL,
    objective_id  TEXT         NOT NULL,
    period_key    TEXT         NOT NULL,
    cadence       TEXT         NOT NULL,
    progress      INT          NOT NULL DEFAULT 0,
    completed     BOOLEAN      NOT NULL DEFAULT FALSE,
    claimed_at    TIMESTAMPTZ,
    PRIMARY KEY (player_uuid, objective_id, period_key)
);

-- Leaderboard / analytics: completions per objective per period.
CREATE INDEX IF NOT EXISTS idx_nop_period_obj_completed
    ON network_objective_progress (period_key, objective_id)
    WHERE completed = TRUE;

-- ──────────────────────────────────────────────────────────────────────────────────────────
-- Part 3: Gems currency.
--         Gems are earned exclusively through network-wide objectives.
--         Each balance mutation appends an audit row to network_gem_transactions.

CREATE TABLE IF NOT EXISTS network_player_gems (
    player_uuid  UUID         NOT NULL PRIMARY KEY,
    player_name  TEXT         NOT NULL,
    balance      BIGINT       NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS network_gem_transactions (
    id            BIGSERIAL    PRIMARY KEY,
    player_uuid   UUID         NOT NULL,
    delta         BIGINT       NOT NULL,          -- positive = credit, negative = debit
    balance_after BIGINT       NOT NULL,
    reason        TEXT         NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ngt_player_time
    ON network_gem_transactions (player_uuid, occurred_at DESC);


-- V30: Profession tracks per player per season.
-- Professions are chosen once per season and grant passive XP-based bonuses.
-- Season is tracked as a string key (e.g. "2026-S1") set in config.

CREATE TABLE IF NOT EXISTS player_professions (
    player_uuid       UUID        NOT NULL,
    season            TEXT        NOT NULL,
    profession        TEXT        NOT NULL,
    xp                INT         NOT NULL DEFAULT 0 CHECK (xp >= 0),
    level             INT         NOT NULL DEFAULT 1 CHECK (level >= 1 AND level <= 50),
    selected_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_reset_at     TIMESTAMPTZ,
    PRIMARY KEY (player_uuid, season)
);

CREATE INDEX IF NOT EXISTS idx_player_professions_season ON player_professions (season);

-- Daily XP cap tracking — resets nightly, prevents AFK farming.
CREATE TABLE IF NOT EXISTS profession_daily_xp (
    player_uuid   UUID  NOT NULL,
    xp_date       DATE  NOT NULL DEFAULT CURRENT_DATE,
    xp_earned     INT   NOT NULL DEFAULT 0 CHECK (xp_earned >= 0),
    PRIMARY KEY (player_uuid, xp_date)
);


-- Suite E: Territory claims upkeep + raid-window metadata hardening

ALTER TABLE survival_claims
    ADD COLUMN IF NOT EXISTS world_name VARCHAR(64) NOT NULL DEFAULT 'world';

ALTER TABLE survival_claims
    ADD COLUMN IF NOT EXISTS upkeep_paid_until TIMESTAMPTZ;

UPDATE survival_claims
SET upkeep_paid_until = COALESCE(upkeep_paid_until, expires_at, NOW() + INTERVAL '7 days')
WHERE upkeep_paid_until IS NULL;

ALTER TABLE survival_claims
    ALTER COLUMN upkeep_paid_until SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_survival_claims_world_bounds
    ON survival_claims (world_name, x1, x2, z1, z2);

CREATE INDEX IF NOT EXISTS idx_survival_claims_upkeep
    ON survival_claims (upkeep_paid_until);


-- V12: Persistent Hologram registry for creator-authored Holograms across all servers
CREATE TABLE IF NOT EXISTS network_holograms (
    id            TEXT        NOT NULL,
    server_id     TEXT        NOT NULL,
    lines         TEXT        NOT NULL DEFAULT '',
    interactive   BOOLEAN     NOT NULL DEFAULT false,
    hitbox_size   INTEGER     NOT NULL DEFAULT 1,
    world_name    TEXT        NOT NULL DEFAULT 'world',
    x             DOUBLE PRECISION NOT NULL,
    y             DOUBLE PRECISION NOT NULL,
    z             DOUBLE PRECISION NOT NULL,
    yaw           REAL        NOT NULL DEFAULT 0,
    pitch         REAL        NOT NULL DEFAULT 0,
    action_type   TEXT        NOT NULL DEFAULT 'NONE',
    action_data   TEXT        NOT NULL DEFAULT '',
    created_by    TEXT        NOT NULL DEFAULT 'system',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, server_id)
);


-- Add parent-child hierarchy support to network_holograms
ALTER TABLE network_holograms 
    ADD COLUMN parent_id VARCHAR(64),
    ADD COLUMN offset_x DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN offset_y DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN offset_z DOUBLE PRECISION DEFAULT 0.0;

ALTER TABLE network_holograms ADD CONSTRAINT fk_hologram_parent FOREIGN KEY (parent_id, server_id) REFERENCES network_holograms(id, server_id);


-- V34: NPC Advanced Attributes
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS skin_url TEXT;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS glow BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS glow_color TEXT;
ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS face_player BOOLEAN NOT NULL DEFAULT false;


ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS hologram_lines TEXT DEFAULT '[]';


ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS skin_signature TEXT;


CREATE TABLE IF NOT EXISTS player_permissions (
    player_id UUID NOT NULL,
    permission VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (player_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_player_permissions_expires_at ON player_permissions(expires_at);


ALTER TABLE network_npcs ADD COLUMN IF NOT EXISTS scale DOUBLE PRECISION NOT NULL DEFAULT 1.0;


ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS billboard VARCHAR(32) NOT NULL DEFAULT 'CENTER';

DROP TABLE IF EXISTS network_rank_audit cascade;
DROP TABLE IF EXISTS network_player_ranks cascade;
DROP TABLE IF EXISTS network_player_admin_overrides cascade;
DROP TABLE IF EXISTS network_ranks cascade;


ALTER TABLE network_holograms ADD COLUMN IF NOT EXISTS scale DOUBLE PRECISION NOT NULL DEFAULT 1.0;


-- V39: Network-wide player nametag layouts (one row per decoration scope bucket)
CREATE TABLE IF NOT EXISTS network_nametags (
    scope              TEXT PRIMARY KEY,
    enabled            BOOLEAN NOT NULL DEFAULT true,
    apply_scopes       TEXT NOT NULL DEFAULT 'global',
    lines              TEXT NOT NULL DEFAULT '',
    base_height        DOUBLE PRECISION NOT NULL DEFAULT 0.32,
    line_spacing       DOUBLE PRECISION NOT NULL DEFAULT 0.27,
    scale              REAL NOT NULL DEFAULT 1.0,
    refresh_ticks      INTEGER NOT NULL DEFAULT 20,
    hide_vanilla_name  BOOLEAN NOT NULL DEFAULT true,
    visible_to_self    BOOLEAN NOT NULL DEFAULT false,
    background         BOOLEAN NOT NULL DEFAULT true,
    created_by         TEXT NOT NULL DEFAULT 'system',
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

