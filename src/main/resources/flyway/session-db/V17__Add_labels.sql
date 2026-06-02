create table Label (
    sessionId uuid not null,
    labelId uuid not null,
    name varchar(255),
    color varchar(64),
    created timestamp(6) with time zone,
    primary key (sessionId, labelId)
);

create index label_sessionid_index on Label (sessionId);

alter table Dataset add column labelIds jsonb;
