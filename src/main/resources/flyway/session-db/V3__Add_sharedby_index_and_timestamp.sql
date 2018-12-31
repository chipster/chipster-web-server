alter table Rule add column created timestamp;
create index rule_sharedby_index on Rule (sharedBy);
