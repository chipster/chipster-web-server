    create table News (
       newsId uuid not null,
        contents jsonb,
        created timestamp,
        modified timestamp,
        primary key (newsId)
    );