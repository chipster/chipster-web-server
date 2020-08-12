alter table jobhistorymodel rename column compName to comp;
alter table jobhistorymodel rename column userName to createdBy;
alter table jobhistorymodel rename column output to screenOutput;
alter table jobhistorymodel rename column jobStatus to state;
alter table jobhistorymodel rename column jobStatusDetail to stateDetail;

alter table jobhistorymodel add column module varchar(255);

alter table jobhistorymodel rename to JobHistory;
