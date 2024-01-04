package fi.csc.chipster.rest.hibernate;

import java.sql.Types;

import org.hibernate.dialect.PostgreSQL95Dialect;

public class ChipsterPostgreSQL95Dialect extends PostgreSQL95Dialect {

	public ChipsterPostgreSQL95Dialect() {
		this.registerColumnType(Types.JAVA_OBJECT, "jsonb");
		// make sure the references to pg_largeobjects are in columns where the vacuumlo
		// command finds them (only CLOB used for now)
		this.registerColumnType(Types.BLOB, "oid");
		this.registerColumnType(Types.CLOB, "oid");
	}
}
