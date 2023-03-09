package fi.csc.chipster.rest.hibernate;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.postgresql.util.PGobject;

import com.fasterxml.jackson.databind.JsonNode;

import fi.csc.chipster.rest.RestUtils;

/**
 * Hibernate UserType for storing JsonNode object as a json blob
 * 
 * JsonNode can include any json structures. The client app or other components
 * can use these fields to save any kind of structured data. Database will store those to
 * plain jsonb column. 
 * 
 * Type jsonb is used in Postgres. The jsonb must be registered in
 * the constructor of Postgres dialect:
 * <pre>
 * this.registerColumnType(Types.JAVA_OBJECT, "jsonb");
 * </pre>
 * 
 * And the type must be registered for the Hibernate:
 * <pre>
 * hibernateConf.put(JsonNodeJsonType.JSON_NODE_JSON_TYPE, new StringJsonType());
 * </pre>
 * 
 * Then you can use this type in the data model classes:
 * <pre>
 * {@literal @}Column
 * {@literal @}Type(type = JsonNodeJsonType.JSON_NODE_JSON_TYPE)
 * private String contents;
 * </pre>
 * 
 * @author klemela
 *
 * @param <T>
 */
public class JsonNodeJsonType implements UserType { 
	
    public static final String JSON_NODE_JSON_TYPE = "JsonNodeJsonType";

	@Override
    public int[] sqlTypes() {
        return new int[]{Types.JAVA_OBJECT};
    }

	@Override
    public Class<JsonNode> returnedClass() {
        return JsonNode.class;
    }
    
    @Override
	public Object nullSafeGet(ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
			throws HibernateException, SQLException {
    	final Object cellContent = rs.getObject(names[0]);
        if (cellContent == null) {
            return null;
        }
        
        if (cellContent instanceof PGobject) {
        	
			String jsonString = ((PGobject)cellContent).getValue();
        	JsonNode jsonObject = RestUtils.parseJson(JsonNode.class, jsonString);
        	
        	return jsonObject;
        } else {
        	throw new HibernateException("unknown type: " + cellContent.getClass().getName());
        }
	}

	@Override
	public void nullSafeSet(PreparedStatement ps, Object value, int idx, SharedSessionContractImplementor session)
			throws HibernateException, SQLException {
		
		int type = Types.OTHER;
		
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
		
		String json = RestUtils.asJson(value);
		
		Object copy = RestUtils.parseJson(JsonNode.class, json);
		return copy;
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