package fi.csc.chipster.rest.hibernate;

import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.PostgreSQLDialect;

public class ChipsterPostgreSQLDialect extends PostgreSQLDialect {

    public ChipsterPostgreSQLDialect() {

        // otherwise the schema export would use default version is 8 where the column
        // type for json is "text" instead of "jsonb"
        // seems to work even if K3s has Postgres 11
        super(DatabaseVersion.make(14, 0));
    }
}
