package fi.csc.chipster.auth;

import java.time.Instant;

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
		
		hibernateSession.save(user);
	}
	
	public void update(User oldUser, User user, Session hibernateSession) {
		user.setModified(Instant.now());
		user.setVersion(oldUser.getVersion());
		
		hibernateSession.merge(user);
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
}
