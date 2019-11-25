    alter table WorkflowRun add column cancellingTimeout int8;
    alter table WorkflowRun add column drainingTimeout int8;
    alter table WorkflowRun add column runningTimeout int8;
    alter table WorkflowRun add column onError int4;
