alter table session alter column notes type oid using notes::oid;
alter table dataset alter column notes type oid using notes::oid;
alter table job alter column screenoutput type oid using screenoutput::oid;
alter table job alter column sourcecode type oid using sourcecode::oid;
alter table job alter column statedetail type oid using statedetail::oid;
alter table job alter column tooldescription type oid using tooldescription::oid;
