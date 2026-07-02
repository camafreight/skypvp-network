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