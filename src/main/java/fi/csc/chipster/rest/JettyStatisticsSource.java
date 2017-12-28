package fi.csc.chipster.rest;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.handler.StatisticsHandler;

public class JettyStatisticsSource implements StatusSource {

	private ConnectionStatistics connectorStats;
	private StatisticsHandler requestStats;

	public JettyStatisticsSource(ConnectionStatistics connectionStats, StatisticsHandler requestStats) {
		this.connectorStats = connectionStats;
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
		status.put("connectionsOpen", connectorStats.getConnections());
		
		// Jetty specific statistics
		status.put("jettyConnectionDurationMax", connectorStats.getConnectionDurationMax());
		status.put("jettyConnectionDurationMean", connectorStats.getConnectionDurationMean());
		status.put("jettyConnectionDurationStdDev", connectorStats.getConnectionDurationStdDev());
		status.put("jettyConnectionsMax", connectorStats.getConnectionsMax());
		status.put("jettyConnectionsTotal", connectorStats.getConnectionsTotal());
		status.put("jettyReceivedBytes", connectorStats.getReceivedBytes());
		status.put("jettyReceivedBytesRate", connectorStats.getReceivedBytesRate());
		status.put("jettyReceivedMessages", connectorStats.getReceivedMessages());
		status.put("jettyReceivedMessagesRate", connectorStats.getReceivedMessagesRate());
		status.put("jettySentBytes", connectorStats.getSentBytes());
		status.put("jettySentBytesRate", connectorStats.getSentBytesRate());
		status.put("jettySentMessages", connectorStats.getSentMessages());
		status.put("jettySentMessagesRate", connectorStats.getSentMessagesRate());
		
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
