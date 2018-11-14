
    drop table if exists Token cascade;

    drop table if exists user_table cascade;

    create table Token (
       tokenKey uuid not null,
        created timestamp,
        rolesJson varchar(255),
        username varchar(255),
        validUntil timestamp,
        primary key (tokenKey)
    );

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
        termsAccepted timestamp,
        termsVersion int4 not null,
        version int8 not null,
        primary key (auth, username)
    );
