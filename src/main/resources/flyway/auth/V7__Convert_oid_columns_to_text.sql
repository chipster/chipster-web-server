-- Move the OIDC code from large objects to a text column. See session-db's
-- V18__Convert_oid_columns_to_text.sql for the reasons and the details.
--
-- The large objects are left in place and become orphans. Remove them afterwards with vacuumlo.

-- text of a large object, or null if the column is null or the large object doesn't exist
create function pg_temp.lo_text(o oid) returns text language sql stable as $$
  select convert_from(lo_get(o), 'UTF8') where exists (select 1 from pg_largeobject_metadata where oid = o)
$$;

alter table oidcloginsession alter column code type text using pg_temp.lo_text(code);
