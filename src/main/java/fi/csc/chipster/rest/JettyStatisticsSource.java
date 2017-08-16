package fi.csc.chipster.rest;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.server.ConnectorStatistics;
import org.eclipse.jetty.server.handler.StatisticsHandler;

public class JettyStatisticsSource implements StatusSource {

	private ConnectorStatistics connectorStats;
	private StatisticsHandler requestStats;

	public JettyStatisticsSource(ConnectorStatistics connectorStats, StatisticsHandler requestStats) {
		this.connectorStats = connectorStats;
		this.requestStats = requestStats;
	}

	@Override
	public Map<String, Object> getStatus() {		
		
		HashMap<String, Object> status = new HashMap<>();	        
			        
		// more or less same with the Jersey
		status.put("requestAverageDuration", requestStats.getRequestTimeMean());
		status.put("requestMaximumDuration", requestStats.getRequestTimeMax());
		
		status.put("requestCount", requestStats.getRequests());
		status.put("requestsPerSecond", perSecond(requestStats.getRequests(), requestStats));				
		
		status.put("responseCodes200", requestStats.getResponses2xx());
		status.put("responseCodes400", requestStats.getResponses4xx());
		status.put("responseCodes500", requestStats.getResponses5xx());
		status.put("responseCodesOther", requestStats.getResponses1xx() + requestStats.getResponses3xx());
		
		// Jetty specific statistics
		status.put("jettyBytesIn", connectorStats.getBytesIn());
		status.put("jettybytesOut", connectorStats.getBytesOut());
		status.put("jettyConnectionsCount", connectorStats.getConnections());
		status.put("jettyConnectionsOpen", connectorStats.getConnectionsOpen());
		status.put("jettyMessagesInPerSecond", connectorStats.getMessagesInPerSecond());
		status.put("jettyMessagesOutPerSecond", connectorStats.getMessagesOutPerSecond());
		status.put("jettyMessagesInCount", connectorStats.getMessagesIn());
		status.put("jettyMessagesOutCount", connectorStats.getMessagesOut());
		
		status.put("jettyRquestsActive", requestStats.getRequestsActive());
		status.put("jettyResponseBytesTotal", requestStats.getResponsesBytesTotal());
		status.put("jettyRequestsActive", requestStats.getRequestsActive());
		status.put("jettyAsyncDispatches", requestStats.getAsyncDispatches());
		status.put("jettyRequestsAsync", requestStats.getAsyncRequests());
		status.put("jettyRequestsAsyncWaiting", requestStats.getAsyncRequestsWaiting());
		status.put("jettyDispatched", requestStats.getDispatched());
		status.put("jettyDispatchedActive", requestStats.getDispatchedActive());	
		
		// should be done with fixed intervals, but what results to return right after the reset?
		requestStats.statsReset();
		
		return status;
	}

	private double perSecond(int requests, StatisticsHandler requestStats2) {
		return requests / (1000.0 * requestStats2.getStatsOnMs());
	}
}
