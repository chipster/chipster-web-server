
    set client_min_messages = WARNING;

    drop table if exists OidcLoginSession cascade;

    drop table if exists user_table cascade;

    create table OidcLoginSession (
        oidcLoginId uuid not null,
        created timestamp(6) with time zone,
        nonce varchar(255),
        oidcName varchar(255),
        state varchar(255),
        primary key (oidcLoginId)
    );

    create table user_table (
        auth varchar(255) not null,
        username varchar(255) not null,
        accessed timestamp(6) with time zone,
        created timestamp(6) with time zone,
        latestSession uuid,
        mail varchar(255),
        modified timestamp(6) with time zone,
        name varchar(255),
        organization varchar(255),
        preferences jsonb,
        termsAccepted timestamp(6) with time zone,
        termsVersion integer not null,
        version bigint not null,
        primary key (auth, username)
    );
