package fi.csc.chipster.auth;

import java.time.Instant;

import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

public class UserTable {

	private HibernateUtil hibernate;

	public UserTable(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
	
	public void add(User user) {
		user.setCreated(Instant.now());
		
		this.hibernate.session().save(user);
	}
	
	public void update(User oldUser, User user) {
		user.setModified(Instant.now());
		user.setVersion(oldUser.getVersion());
		
		this.hibernate.session().merge(user);
	}
	
	public void addOrUpdate(User user) {
		
		User oldUser = get(user.getUserId());
		
		if (oldUser != null) {
			update(oldUser, user);
		} else {
			add(user);
		}
	}
	
	public User get(UserId userId) {
		
		User user = this.hibernate.session().get(User.class, userId);
		
		//user.setAccessed(Instant.now());
		
		return user;
	}
}
