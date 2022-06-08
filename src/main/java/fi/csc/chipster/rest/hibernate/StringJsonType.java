package fi.csc.chipster.rest.hibernate;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Hibernate UserType for storing a String as a json blob
 * 
 * Type jsonb is used in Postgres and clob in H2 database. The jsonb must be registered in
 * the constructor of Postgres dialect:
 * <pre>
 * this.registerColumnType(Types.JAVA_OBJECT, "jsonb");
 * </pre>
 * 
 * And the type must be registered for the Hibernate:
 * <pre>
 * hibernateConf.put(StringJsonType.STRING_JSON_TYPE, new StringJsonType());
 * </pre>
 * 
 * Then you can use this type in the data model classes:
 * <pre>
 * {@literal @}Column
 * {@literal @}Type(type = StringJsonType.STRING_JSON_TYPE)
 * private String contents;
 * </pre>
 * 
 * @author klemela
 *
 * @param <T>
 */
public class StringJsonType implements UserType { 
	
    public static final String STRING_JSON_TYPE = "StringJsonType";
	private boolean fallbackToClob;

	@Override
    public int[] sqlTypes() {
		if (fallbackToClob) {
			return new int[]{Types.CLOB};
		}
        return new int[]{Types.JAVA_OBJECT};
    }

	@Override
    public Class<? extends String> returnedClass() {
        return String.class;
    }
    
    @Override
	public Object nullSafeGet(ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
			throws HibernateException, SQLException {
    	final String cellContent = rs.getString(names[0]);
        if (cellContent == null) {
            return null;
        }
        
		return cellContent;
	}

	@Override
	public void nullSafeSet(PreparedStatement ps, Object value, int idx, SharedSessionContractImplementor session)
			throws HibernateException, SQLException {
		
		int type = fallbackToClob ? Types.CLOB : Types.OTHER;
		
		if (value == null) {
            ps.setObject(idx, null, type);
        } else {
			    	
	        ps.setObject(idx, value, type);
        }
	}

	@Override
    public Object deepCopy(final Object value) throws HibernateException {
		if (value == null) {
			return null;
		}
    	return new String((String)value); 	
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(final Object value) throws HibernateException {
        return (Serializable) this.deepCopy(value);
    }

    @Override
    public Object assemble(final Serializable cached, final Object owner) throws HibernateException {
        return this.deepCopy(cached);
    }

    @Override
    public Object replace(final Object original, final Object target, final Object owner) throws HibernateException {
        return this.deepCopy(original);
    }
    
    @Override
    public boolean equals(final Object obj1, final Object obj2) throws HibernateException {
        if (obj1 == null) {
            return obj2 == null;
        }
        return obj1.equals(obj2);
    }

    @Override
    public int hashCode(final Object obj) throws HibernateException {
        return obj.hashCode();
    }
}