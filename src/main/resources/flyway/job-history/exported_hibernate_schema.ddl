
    set client_min_messages = WARNING;

    drop table if exists JobHistory cascade;

    create table JobHistory (
        jobId uuid not null,
        sessionId uuid not null,
        comp varchar(255),
        created timestamp(6) with time zone,
        createdBy varchar(255),
        endTime timestamp(6) with time zone,
        memoryUsage bigint,
        module varchar(255),
        screenOutput oid,
        startTime timestamp(6) with time zone,
        state varchar(255),
        stateDetail varchar(255),
        storageUsage bigint,
        toolId varchar(255),
        toolName varchar(255),
        primary key (jobId, sessionId)
    );

    create index job_history_created_index 
       on JobHistory (created desc);
