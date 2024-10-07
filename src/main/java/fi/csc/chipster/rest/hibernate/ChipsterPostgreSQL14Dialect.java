package fi.csc.chipster.rest.hibernate;

import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;

public class ChipsterPostgreSQL14Dialect extends PostgreSQLDialect {

    public ChipsterPostgreSQL14Dialect() {

        // otherwise the schema export would use default version is 8 where the column
        // type for json is "text" instead of "jsonb"
        super(DatabaseVersion.make(14, 0));
    }
}
