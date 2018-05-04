package fi.csc.chipster.rest.hibernate;

import java.sql.Types;

public class ChipsterH2Dialect extends org.hibernate.dialect.H2Dialect {
	public ChipsterH2Dialect() {
		// workaround to fix the Hibernate schema validation
		// https://hibernate.atlassian.net/browse/HHH-9835 
		registerColumnType(Types.BINARY, "varbinary");
	}
}
