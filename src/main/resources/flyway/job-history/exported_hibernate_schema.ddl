
    drop table if exists JobHistory cascade;

    create table JobHistory (
       jobId uuid not null,
        sessionId uuid not null,
        comp varchar(255),
        created timestamp,
        createdBy varchar(255),
        endTime timestamp,
        memoryUsage int8,
        module varchar(255),
        screenOutput oid,
        startTime timestamp,
        state varchar(255),
        stateDetail varchar(255),
        toolId varchar(255),
        toolName varchar(255),
        primary key (jobId, sessionId)
    );
create index job_history_created_index on JobHistory (created desc);
