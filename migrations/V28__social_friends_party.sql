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
