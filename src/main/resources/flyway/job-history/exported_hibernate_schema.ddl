
    drop table if exists JobHistoryModel cascade;

    create table JobHistoryModel (
       jobId uuid not null,
        sessionId uuid not null,
        compName varchar(255),
        endTime timestamp,
        jobStatus varchar(255),
        output oid,
        startTime timestamp,
        timeDuration varchar(255),
        toolId varchar(255),
        toolName varchar(255),
        userName varchar(255),
        primary key (jobId, sessionId)
    );
create index job_history_start_time_index on JobHistoryModel (startTime desc);
