package fi.csc.chipster.rest.hibernate;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import fi.csc.chipster.rest.RestUtils;

/**
 * Hibernate UserType for storing an object as a json blob
 * 
 * @author klemela
 *
 * @param <T>
 */
public class JsonType<T extends DeepCopyable> implements UserType { 
	
    private boolean fallbackToClob;
	private Class<T> innerType;
    
	/**
	 * @param fallbackToClob
	 * @param innerType There is no generic type available in runtime, so we it has to be given also here
	 */
	public JsonType(boolean fallbackToClob, Class<T> innerType) {
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
    public Class<? extends Class<T>> returnedClass() {
        return (Class<? extends Class<T>>) innerType.getClass();
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
        
		return RestUtils.parseJson(returnedClass(), cellContent);
	}

	@Override
	public void nullSafeSet(PreparedStatement ps, Object value, int idx, SharedSessionContractImplementor session)
			throws HibernateException, SQLException {
		
		int type = fallbackToClob ? Types.CLOB : Types.OTHER;
		
		if (value == null) {
            ps.setObject(idx, null, type);
        } else {
		
	    	String json = RestUtils.asJson(value);	    	
	        ps.setObject(idx, json, type);
        }
	}

    @SuppressWarnings("unchecked")
	@Override
    public Object deepCopy(final Object value) throws HibernateException {
		if (value == null) {
			return null;
		}
    	return ((T)value).deepCopy();    	
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