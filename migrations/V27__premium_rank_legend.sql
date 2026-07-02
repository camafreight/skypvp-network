insert into network_ranks (rank_key, display_name, priority, prefix, chat_color)
values
    ('legend', 'Legend', 450, '[Legend] ', 'gold')
on conflict (rank_key) do update
    set display_name = excluded.display_name,
        priority     = excluded.priority,
        prefix       = excluded.prefix,
        chat_color   = excluded.chat_color;