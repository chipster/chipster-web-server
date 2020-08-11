
    drop table if exists JobHistoryModel cascade;

    create table JobHistoryModel (
       jobId uuid not null,
        sessionId uuid not null,
        compName varchar(255),
        created timestamp,
        endTime timestamp,
        jobStatus varchar(255),
        jobStatusDetail varchar(255),
        memoryUsage int8,
        output text,
        startTime timestamp,
        timeDuration varchar(255),
        toolId varchar(255),
        toolName varchar(255),
        userName varchar(255),
        primary key (jobId, sessionId)
    );
create index job_history_created_index on JobHistoryModel (created desc);
