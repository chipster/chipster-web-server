
    drop table if exists user_table cascade;

    create table user_table (
       auth varchar(255) not null,
        username varchar(255) not null,
        accessed timestamp,
        created timestamp,
        latestSession uuid,
        mail varchar(255),
        modified timestamp,
        name varchar(255),
        organization varchar(255),
        preferences jsonb,
        termsAccepted timestamp,
        termsVersion int4 not null,
        version int8 not null,
        primary key (auth, username)
    );
