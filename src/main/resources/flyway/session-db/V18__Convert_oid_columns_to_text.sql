-- Move the long texts from large objects to text columns.
--
-- Hibernate stored the @Lob String fields as large objects, so that the table had only the
-- number (oid) of the large object and reading a row needed several more round trips to the
-- database for each text. Text columns are read in the same query, and values longer than
-- about 2 kB are compressed and stored in the table's TOAST storage by Postgres.
--
-- The texts were written as UTF-8 (checked with Hibernate 7.3 and pgjdbc 42.7). convert_from()
-- fails if a large object isn't valid UTF-8, so the migration stops instead of storing
-- broken text.
--
-- The large objects are left in place and become orphans. Remove them afterwards with vacuumlo:
-- unlinking them here would need a lock for each one in the same transaction and fails with
-- "out of shared memory" on a real database.

-- text of a large object, or null if the column is null or the large object doesn't exist
create function pg_temp.lo_text(o oid) returns text language sql stable as $$
  select convert_from(lo_get(o), 'UTF8') where exists (select 1 from pg_largeobject_metadata where oid = o)
$$;

alter table session alter column notes type text using pg_temp.lo_text(notes);

alter table dataset alter column notes type text using pg_temp.lo_text(notes);

-- one statement, so that the table is rewritten only once
alter table job
  alter column screenoutput type text using pg_temp.lo_text(screenoutput),
  alter column sourcecode type text using pg_temp.lo_text(sourcecode),
  alter column statedetail type text using pg_temp.lo_text(statedetail),
  alter column tooldescription type text using pg_temp.lo_text(tooldescription);
