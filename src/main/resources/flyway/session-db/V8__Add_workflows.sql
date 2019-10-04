    create table WorkflowPlan (
       sessionId uuid not null,
        workflowPlanId uuid not null,
        created timestamp,
        name varchar(255),
        notes oid,
        originalDuration int8,
        workflowJobPlans jsonb,
        primary key (sessionId, workflowPlanId)
    );

    create table WorkflowRun (
       sessionId uuid not null,
        workflowRunId uuid not null,
        created timestamp,
        createdBy varchar(255),
        currentJobPlanId varchar(255),
        currentWorkflowJobPlanId varchar(255),
        endTime timestamp,
        state int4,
        workflowPlanId varchar(255),
        primary key (sessionId, workflowRunId)
    );

    create index workflowplan_sessionid_index on WorkflowPlan (sessionId);
    create index workflowrun_sessionid_index on WorkflowRun (sessionId);
