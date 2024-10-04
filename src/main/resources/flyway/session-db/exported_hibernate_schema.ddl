
    alter table if exists Dataset 
       drop constraint if exists FKtnwjerv439jr4lmc37uvdp6ha;

    alter table if exists Rule 
       drop constraint if exists FKmf1c6t4ld9isrgivjddortper;

    drop table if exists Dataset cascade;

    drop table if exists File cascade;

    drop table if exists Job cascade;

    drop table if exists News cascade;

    drop table if exists Rule cascade;

    drop table if exists Session cascade;

    create table Dataset (
       datasetId uuid not null,
        sessionId uuid not null,
        created timestamp(6) with time zone,
        metadataFiles jsonb,
        name varchar(255),
        notes oid,
        sourceJob uuid,
        x integer,
        y integer,
        fileId uuid,
        primary key (datasetId, sessionId)
    );

    create table File (
       fileId uuid not null,
        checksum varchar(255),
        encryptionKey varchar(255),
        fileCreated timestamp(6) with time zone,
        size bigint not null,
        state smallint,
        storage varchar(255),
        primary key (fileId)
    );

    create table Job (
       jobId uuid not null,
        sessionId uuid not null,
        comp varchar(255),
        cpuLimit integer,
        created timestamp(6) with time zone,
        createdBy varchar(255),
        endTime timestamp(6) with time zone,
        inputs jsonb,
        memoryLimit bigint,
        memoryUsage bigint,
        metadataFiles jsonb,
        module varchar(255),
        outputs jsonb,
        parameters jsonb,
        screenOutput oid,
        sourceCode oid,
        startTime timestamp(6) with time zone,
        state smallint,
        stateDetail oid,
        storageUsage bigint,
        toolCategory varchar(255),
        toolDescription oid,
        toolId varchar(255),
        toolName varchar(255),
        primary key (jobId, sessionId)
    );

    create table News (
       newsId uuid not null,
        contents jsonb,
        created timestamp(6) with time zone,
        modified timestamp(6) with time zone,
        primary key (newsId)
    );

    create table Rule (
       ruleId uuid not null,
        created timestamp(6) with time zone,
        readWrite boolean not null,
        sharedBy varchar(255),
        username varchar(255),
        sessionId uuid,
        primary key (ruleId)
    );

    create table Session (
       sessionId uuid not null,
        accessed timestamp(6) with time zone,
        created timestamp(6) with time zone,
        name varchar(255),
        notes oid,
        state smallint,
        primary key (sessionId)
    );
create index dataset_fileid_index on Dataset (fileId);
create index dataset_sessionid_index on Dataset (sessionId);
create index job_sessionid_index on Job (sessionId);
create index rule_username_index on Rule (username);
create index rule_sessionid_index on Rule (sessionId);
create index rule_sharedby_index on Rule (sharedBy);

    alter table if exists Dataset 
       add constraint FKtnwjerv439jr4lmc37uvdp6ha 
       foreign key (fileId) 
       references File;

    alter table if exists Rule 
       add constraint FKmf1c6t4ld9isrgivjddortper 
       foreign key (sessionId) 
       references Session;
