package fi.csc.chipster.rest.hibernate;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import fi.csc.chipster.rest.RestUtils;

/**
 * Hibernate UserType for storing a list of objects as a json blob
 * 
 * Type jsonb is used in Postgres and clob in H2 database. The jsonb must be registered in
 * the constructor of Postgre dialect:
 * <pre>
 * this.registerColumnType(Types.JAVA_OBJECT, "jsonb");
 * </pre>
 * 
 * And the type must be registered for the Hibernate:
 * <pre>
 * hibernateConf.registerTypeOverride(new ListJsonType<MetadataEntry>(h2, MetadataEntry.class), new String[] {MetadataEntry.METADATA_ENTRY_LIST_TYPE});
 * </pre>
 * 
 * Then you can use this type in the data model classes:
 * <pre>
 * {@literal @}Column
 * {@literal @}Type(type = MetadataEntry.METADATA_ENTRY_LIST_TYPE)
 * private Collection<MetadataEntry> metadata;
 * </pre>
 * 
 * @author klemela
 *
 * @param <T>
 */
public class ListJsonType<T extends DeepCopyable> implements UserType { 
	
    private boolean fallbackToClob;
	private Class<T> innerType;
    
	/**
	 * @param fallbackToClob
	 * @param innerType There is no generic type available in runtime, so we it has to be given also here
	 */
	public ListJsonType(boolean fallbackToClob, Class<T> innerType) {
    	this.fallbackToClob = fallbackToClob;
    	this.innerType = innerType;
	}

	@Override
    public int[] sqlTypes() {
		if (fallbackToClob) {
			return new int[]{Types.CLOB};
		}
        return new int[]{Types.JAVA_OBJECT};
    }

    @SuppressWarnings("unchecked")
	@Override
    public Class<? extends ArrayList<T>> returnedClass() {
        return (Class<? extends ArrayList<T>>) new ArrayList<T>().getClass();
    }
    
    public Class<T> returnedClassInner() {
        return innerType;
    }
    
    @Override
	public Object nullSafeGet(ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
			throws HibernateException, SQLException {
    	final String cellContent = rs.getString(names[0]);
        if (cellContent == null) {
            return null;
        }
        
		return RestUtils.parseJson(returnedClass(), returnedClassInner(), cellContent);
	}

	@Override
	public void nullSafeSet(PreparedStatement ps, Object value, int idx, SharedSessionContractImplementor session)
			throws HibernateException, SQLException {
		if (value == null) {
            return;
        }
		
    	String json = RestUtils.asJson(value);
    	
    	int type = fallbackToClob ? Types.CLOB : Types.OTHER; 
        ps.setObject(idx, json, type);
	}

    @SuppressWarnings("unchecked")
	@Override
    public Object deepCopy(final Object value) throws HibernateException {
		
    	return ((ArrayList<T>)value).stream()
    			.map(e -> e.deepCopy())
    			.collect(Collectors.toList());    	
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