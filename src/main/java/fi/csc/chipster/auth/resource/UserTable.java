package fi.csc.chipster.auth.resource;

import java.time.Instant;
import java.util.List;

import jakarta.ws.rs.NotFoundException;

import org.hibernate.Session;

import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

public class UserTable {

	private HibernateUtil hibernate;

	public UserTable(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
	
	public void add(User user, Session hibernateSession) {
		user.setCreated(Instant.now());
		HibernateUtil.persist(user, hibernateSession);
	}
	
	public void update(User user) {
		User oldUser = get(user.getUserId(), hibernate.session());
		
		if (oldUser != null) {
			update(oldUser, user, hibernate.session());
		} else {
			throw new NotFoundException("user not found");
		}
	}
	
	public void update(User oldUser, User user, Session hibernateSession) {
		
		// unless the approval is updated 
		if (user.getTermsVersion() < oldUser.getTermsVersion()) {
			// keep the old approval
			user.setTermsVersion(oldUser.getTermsVersion());
			user.setTermsAccepted(oldUser.getTermsAccepted());
		}
		
		user.setModified(Instant.now());
		user.setVersion(oldUser.getVersion());
		
		HibernateUtil.update(user, user.getUserId(), hibernateSession);
	}
	
	public void addOrUpdate(User user, Session hibernateSession) {
		
		User oldUser = get(user.getUserId(), hibernateSession);
		
		if (oldUser != null) {
			update(oldUser, user, hibernateSession);
		} else {
			add(user, hibernateSession);
		}
	}
	
	public User get(UserId userId, Session hibernateSession) {
		
		User user = hibernateSession.get(User.class, userId);
		
		//user.setAccessed(Instant.now());
		
		return user;
	}

	public void addOrUpdate(User user) {
		this.addOrUpdate(user, this.hibernate.session());
	}

	public User get(UserId userId) {
		return get(userId, this.hibernate.session());
	}

	@SuppressWarnings("unchecked")
	public List<User> getAll() {
		return this.hibernate.session().createQuery("from " + User.class.getSimpleName()).list();
	}
}
