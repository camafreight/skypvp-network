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
