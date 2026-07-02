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
