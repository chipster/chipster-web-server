alter table jobhistorymodel add column created timestamp;
update jobhistorymodel set created = startTime;
update jobhistorymodel set created = now()::timestamp where created is NULL;
create index job_history_created_index on JobHistoryModel (created);
drop index job_history_start_time_index;

alter table jobhistorymodel add column memoryUsage int8;
