package fi.csc.chipster.sessiondb.resource;

import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.DatasetToken;
import fi.csc.chipster.sessiondb.model.Session;

public class DatasetTokenTable {

	public static final Logger logger = LogManager.getLogger();

	
	private static final int CLEAN_UP_INTERVAL = 60; // minutes 
	
	private HibernateUtil hibernate;

	private Timer cleanUpTimer;

	public DatasetTokenTable(HibernateUtil hibernate) {
		this.hibernate = hibernate;
		
		long cleanUpIntervalMs = CLEAN_UP_INTERVAL * 1000 * 60;
		
		this.cleanUpTimer = new Timer("dataset clean up timer", true);
		this.cleanUpTimer.schedule(new TimerTask() {			
			@Override
			public void run() {
				try {
					hibernate.runInTransaction(new HibernateRunnable<Void>() {
						@Override
						public Void run(org.hibernate.Session hibernateSession) {
							cleanUp(hibernateSession);
							return null;
						}
					});
				} catch (Exception e) {
					logger.warn("dataset token clean up failed", e);
				}
			}
		}, cleanUpIntervalMs, cleanUpIntervalMs);
	}
	
    public DatasetToken checkAuthorization(UUID tokenKey, UUID sessionId, UUID datasetId) {
    	return checkAuthorization(tokenKey, sessionId, datasetId, hibernate.session());
    }
    
	public DatasetToken checkAuthorization(UUID tokenKey, UUID sessionId, UUID datasetId, org.hibernate.Session hibernateSession) {

		if(tokenKey == null) {
			throw new NotAuthorizedException("dataset token is null");
		}
		
		Session session = hibernateSession.get(Session.class, sessionId);
		
		if (session == null) {
			throw new NotFoundException("session not found");
		}
		
		Dataset dataset = hibernateSession.get(Dataset.class, datasetId);
		
		if (dataset == null) {
			throw new NotFoundException("dataset not found");
		}
		
		DatasetToken token = hibernateSession.get(DatasetToken.class, tokenKey);
		
		if (token == null) {
			throw new ForbiddenException("token not found");
		}
		
		if (!sessionId.equals(token.getSession().getSessionId())) {
			throw new ForbiddenException("token not valid for this session");
		}
		
		if (!datasetId.equals(token.getDataset().getDatasetId())) {
			throw new ForbiddenException("token not valid for this dataset");			
		}
		
		if (token.getValid().isBefore(Instant.now())) {			
			throw new ForbiddenException("token expired");
		}
		
		return token;
	}

	public void save(DatasetToken datasetToken) {
		hibernate.session().save(datasetToken);
	}

	public void cleanUp(org.hibernate.Session hibernateSession) {
		String hql = "delete from " + DatasetToken.class.getSimpleName() + " where valid< :valid";
		int result = hibernateSession.createQuery(hql).setParameter("valid", Instant.now()).executeUpdate();
		if (result > 0) {
			logger.info(result + " expired dataset tokens removed");
		}
	}
}
