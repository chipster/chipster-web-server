alter table Job add column metadataFiles jsonb;
alter table Dataset add column metadataFiles jsonb;
alter table Dataset drop column metadata;
