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

    create index workflowplan_sessionid_index on WorkflowPlan (sessionId);
    create index workflowrun_sessionid_index on WorkflowRun (sessionId);

    alter table Dataset add column sourceJobOutputId varchar(255);
