
    alter table if exists Dataset 
       drop constraint if exists FKtnwjerv439jr4lmc37uvdp6ha;

    alter table if exists Rule 
       drop constraint if exists FKmf1c6t4ld9isrgivjddortper;

    drop table if exists Dataset cascade;

    drop table if exists File cascade;

    drop table if exists Job cascade;

    drop table if exists Rule cascade;

    drop table if exists Session cascade;

    drop table if exists WorkflowPlan cascade;

    drop table if exists WorkflowRun cascade;

    create table Dataset (
       datasetId uuid not null,
        sessionId uuid not null,
        created timestamp,
        metadataFiles jsonb,
        name varchar(255),
        notes oid,
        sourceJob uuid,
        sourceJobOutputId varchar(255),
        x int4,
        y int4,
        fileId uuid,
        primary key (datasetId, sessionId)
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
        metadataFiles jsonb,
        module varchar(255),
        parameters jsonb,
        screenOutput oid,
        sourceCode oid,
        startTime timestamp,
        state int4,
        stateDetail oid,
        toolCategory varchar(255),
        toolDescription oid,
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
        notes oid,
        state int4,
        primary key (sessionId)
    );

    create table WorkflowPlan (
       sessionId uuid not null,
        workflowPlanId uuid not null,
        created timestamp,
        name varchar(255),
        notes oid,
        originalDuration int8,
        workflowJobs jsonb,
        primary key (sessionId, workflowPlanId)
    );

    create table WorkflowRun (
       sessionId uuid not null,
        workflowRunId uuid not null,
        created timestamp,
        createdBy varchar(255),
        endTime timestamp,
        name varchar(255),
        state int4,
        workflowJobs jsonb,
        primary key (sessionId, workflowRunId)
    );
create index dataset_fileid_index on Dataset (fileId);
create index dataset_sessionid_index on Dataset (sessionId);
create index job_sessionid_index on Job (sessionId);
create index rule_username_index on Rule (username);
create index rule_sessionid_index on Rule (sessionId);
create index rule_sharedby_index on Rule (sharedBy);
create index workflowplan_sessionid_index on WorkflowPlan (sessionId);
create index workflowrun_sessionid_index on WorkflowRun (sessionId);

    alter table if exists Dataset 
       add constraint FKtnwjerv439jr4lmc37uvdp6ha 
       foreign key (fileId) 
       references File;

    alter table if exists Rule 
       add constraint FKmf1c6t4ld9isrgivjddortper 
       foreign key (sessionId) 
       references Session;
