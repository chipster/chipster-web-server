
    alter table if exists Dataset 
       drop constraint if exists FKtnwjerv439jr4lmc37uvdp6ha;

    alter table if exists DatasetToken 
       drop constraint if exists FK2vdi25wvt3n7gd4wadatk8np8;

    alter table if exists DatasetToken 
       drop constraint if exists FK2ld1310doy7l3b9t7yp71ni37;

    alter table if exists Rule 
       drop constraint if exists FKmf1c6t4ld9isrgivjddortper;

    drop table if exists Dataset cascade;

    drop table if exists DatasetToken cascade;

    drop table if exists File cascade;

    drop table if exists Job cascade;

    drop table if exists Rule cascade;

    drop table if exists Session cascade;

    create table Dataset (
       datasetId uuid not null,
        sessionId uuid not null,
        created timestamp,
        metadata jsonb,
        name varchar(255),
        notes text,
        sourceJob uuid,
        x int4,
        y int4,
        fileId uuid,
        primary key (datasetId, sessionId)
    );

    create table DatasetToken (
       tokenKey uuid not null,
        username varchar(255),
        valid timestamp,
        dataset_datasetId uuid,
        dataset_sessionId uuid,
        session_sessionId uuid,
        primary key (tokenKey)
    );

    create table File (
       fileId uuid not null,
        checksum varchar(255),
        fileCreated timestamp,
        size int8 not null,
        primary key (fileId)
    );

    create table Job (
       jobId uuid not null,
        sessionId uuid not null,
        created timestamp,
        createdBy varchar(255),
        endTime timestamp,
        inputs jsonb,
        memoryUsage int8,
        module varchar(255),
        parameters jsonb,
        screenOutput text,
        sourceCode text,
        startTime timestamp,
        state int4,
        stateDetail text,
        toolCategory varchar(255),
        toolDescription text,
        toolId varchar(255),
        toolName varchar(255),
        primary key (jobId, sessionId)
    );

    create table Rule (
       ruleId uuid not null,
        created timestamp,
        readWrite boolean not null,
        sharedBy varchar(255),
        username varchar(255),
        sessionId uuid,
        primary key (ruleId)
    );

    create table Session (
       sessionId uuid not null,
        accessed timestamp,
        created timestamp,
        name varchar(255),
        notes text,
        state int4,
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

    alter table if exists DatasetToken 
       add constraint FK2vdi25wvt3n7gd4wadatk8np8 
       foreign key (dataset_datasetId, dataset_sessionId) 
       references Dataset;

    alter table if exists DatasetToken 
       add constraint FK2ld1310doy7l3b9t7yp71ni37 
       foreign key (session_sessionId) 
       references Session;

    alter table if exists Rule 
       add constraint FKmf1c6t4ld9isrgivjddortper 
       foreign key (sessionId) 
       references Session;
