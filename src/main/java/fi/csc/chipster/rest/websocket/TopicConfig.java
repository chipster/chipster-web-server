package fi.csc.chipster.rest.websocket;

import java.util.List;

import fi.csc.chipster.auth.resource.AuthPrincipal;

/**
 * Topic configuration
 * 
 * Implement this interface to tell PubSubServer how to handle different topics.
 * 
 * @author klemela
 */
public interface TopicConfig {
	/**
	 * Check whether a user is allowed to subscribe to a topic
	 * 
	 * @param principal
	 * @param topicName
	 * @return
	 */
	public boolean isAuthorized(AuthPrincipal principal, String topicName);

	/**
	 * Proper implementation for this method is needed only if you need more fine
	 * grained
	 * monitoring statistics. Otherwise, simply return an empty string "".
	 * 
	 * Count some monitoring statistics separately for different topic groups.
	 * Return a same
	 * string for all the topics that belong to the same topic group. This string is
	 * also appended
	 * to the keys of the monitoring results.
	 * 
	 * @param topicName
	 * @return
	 */
	public String getMonitoringTag(String topicName);

	/**
	 * Proper implementation for this method is needed only if you need more fine
	 * grained
	 * monitoring statistics. Otherwise, simply return Arrays.asList(new String[]
	 * {""}).
	 * 
	 * All possible tag values of the above method. This is needed to produce zero
	 * values in the statistics
	 * even when a topic group doesn't have any topics.
	 * 
	 * @return
	 */
	public List<String> getMonitoringTags();

	/**
	 * Check the authenticity of the tokenKey and return a Principal object of the
	 * authenticated user
	 * 
	 * @param tokenKey
	 * @return
	 */
	public AuthPrincipal getUserPrincipal(String tokenKey);
}
