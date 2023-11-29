package fi.csc.chipster.auth.resource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;

import com.fasterxml.jackson.databind.JsonNode;

import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import jakarta.ws.rs.NotFoundException;

public class UserTable {
    
    private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	public UserTable(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}	
	
	public User get(UserId userId, Session hibernateSession) {
		
		User user = hibernateSession.get(User.class, userId);
		
		// turning all read requests to writes is probably a terrible idea performance-wise
		//user.setAccessed(Instant.now());
		
		return user;
	}

	public User get(UserId userId) {
		return get(userId, this.hibernate.session());
	}

	@SuppressWarnings("unchecked")
	public List<User> getAll() {
		return this.hibernate.session().createQuery("from " + User.class.getSimpleName()).list();
	}

    public void addOrUpdateFromLogin(UserId userId, String email, String organization, String name) {
        
        this.addOrUpdateFromLogin(userId, email, organization, name, this.hibernate.session());
    }
    
    public void addOrUpdateFromLogin(UserId userId, String email, String organization, String name, Session hibernateSession) {
                
        User user = get(userId, hibernateSession);
        
        if (user != null) {
            
            user.setMail(email);
            user.setOrganization(organization);
            user.setName(name);
            
            update(user, hibernateSession, Instant.now());
            
        } else {
            user = new User(userId.getAuth(), userId.getUsername(), email, organization, name);
            add(user, hibernateSession);
        }
    }


    public void updateFromClient(String userId, UUID latestSession, JsonNode preferences, int termsVersion) {
        
        User user = get(new UserId(userId), this.hibernate.session());
        
        if (user == null) {
            throw new NotFoundException("user not found");
        }
        
        Instant now = Instant.now();
        
        // terms approved
        if (termsVersion > user.getTermsVersion()) {
            // store timestamp from server time
            user.setTermsVersion(termsVersion);
            user.setTermsAccepted(now);
        }
        
        // someone tried to accept older version
        if (termsVersion != 0 && termsVersion < user.getTermsVersion()) {
            logger.warn("userId " + user.getUserId() + " tried to accept terms-of-use version "
                    + user.getTermsVersion() + ", but user has accepted already version "
                    + user.getTermsVersion() + ". Keeping the old approval.");
        }
        
        user.setLatestSession(latestSession);
        user.setPreferences(preferences);
        
        this.update(user, this.hibernate.session(), now);
    }
    
    public void add(User user, Session hibernateSession) {
        Instant now = Instant.now();
        user.setCreated(now);
        user.setModified(now);
        HibernateUtil.persist(user, hibernateSession);
    }
    
    public void update(User user, Session hibernateSession, Instant now) {              
                
        if (user.getCreated() == null) {
            // user.created wasn't preserved before 12/2023. Set now to be able clean up old users at some point later
            user.setCreated(now);
        }       
        
        /* It's useful to know when a user has used Chipster last time to be able to delete old user accounts
         * 
         * Now we can use user.modified for this purpose, because we update the user.modified on every login and also 
         * the web-app updates the user object on every session opening. If would update this timestamp only when 
         * something has really changed, we would need another field for the last login timestamp.
         */
        user.setModified(now);
        
        // why modifications are not saved without this?
        hibernateSession.detach(user);
        HibernateUtil.update(user, user.getUserId(), hibernateSession);     
    }

}
