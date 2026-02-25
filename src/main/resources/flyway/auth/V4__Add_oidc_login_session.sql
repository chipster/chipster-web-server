drop table if exists OidcLoginSession cascade;

create table OidcLoginSession (
    oidcLoginId uuid not null,
    created timestamp(6) with time zone,
    nonce varchar(255),
    oidcName varchar(255),
    state varchar(255),
    primary key (oidcLoginId)
);
