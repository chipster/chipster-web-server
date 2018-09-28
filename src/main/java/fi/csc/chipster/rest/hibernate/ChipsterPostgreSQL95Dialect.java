package fi.csc.chipster.rest.hibernate;

import java.sql.Types;

import org.hibernate.dialect.PostgreSQL95Dialect;

public class ChipsterPostgreSQL95Dialect extends PostgreSQL95Dialect {

	public ChipsterPostgreSQL95Dialect() {
		this.registerColumnType(Types.JAVA_OBJECT, "jsonb");
	}
}
