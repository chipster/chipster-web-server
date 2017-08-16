package fi.csc.chipster.sessiondb;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.ext.Provider;

import org.glassfish.jersey.server.monitoring.MonitoringStatistics;
import org.glassfish.jersey.server.monitoring.MonitoringStatisticsListener;

@Provider
public class StatisticsListener implements MonitoringStatisticsListener {
	
	private HashMap<String, Object> latestStatistics = new HashMap<>();

	@Override
	public void onStatistics(MonitoringStatistics statistics) {
		
		
		latestStatistics.put("exceptionMappingsSuccessful", statistics.getExceptionMapperStatistics().getSuccessfulMappings());
		latestStatistics.put("exceptionMappingsTotal", statistics.getExceptionMapperStatistics().getTotalMappings());
		latestStatistics.put("exceptionMappingsUnsuccessful", statistics.getExceptionMapperStatistics().getUnsuccessfulMappings());
		latestStatistics.put("requestAverageDuration", statistics.getRequestStatistics().getTimeWindowStatistics().get(15000l).getAverageDuration());
		latestStatistics.put("requestMaximumDuration", statistics.getRequestStatistics().getTimeWindowStatistics().get(15000l).getMaximumDuration());
		latestStatistics.put("requestMinimumDuration", statistics.getRequestStatistics().getTimeWindowStatistics().get(15000l).getMinimumDuration());
		latestStatistics.put("requestCount", statistics.getRequestStatistics().getTimeWindowStatistics().get(15000l).getRequestCount());
		latestStatistics.put("requestsPerSecond", statistics.getRequestStatistics().getTimeWindowStatistics().get(15000l).getRequestsPerSecond());
		
		long responseCodes200 = 0;
		long responseCodes400 = 0;
		long responseCodes500 = 0;
		long responseCodesOther = 0;
		
		for (int code : statistics.getResponseStatistics().getResponseCodes().keySet()) {
			long count = statistics.getResponseStatistics().getResponseCodes().get(code);
			
			if (code/100 == 2) {
				responseCodes200 += count;
			} else if (code/100 == 4) {
				responseCodes400 += count;
			} else if (code/100 == 5) {
				responseCodes500 += count;
			} else {
				responseCodesOther += count;
			}
		}
		
		latestStatistics.put("responseCodes200", responseCodes200);
		latestStatistics.put("responseCodes400", responseCodes400);
		latestStatistics.put("responseCodes500", responseCodes500);
		latestStatistics.put("responseCodesOther", responseCodesOther);
		
		// more verbose statistics 
		
		// statistics by resource
		//System.out.println("statistics " + statistics.getResourceClassStatistics());

		// statistics by url
		//System.out.println("statistics " + statistics.getUriStatistics());
		
		//System.out.println("statistics " + statistics.getExceptionMapperStatistics().getExceptionMapperExecutions());
	}

	public Map<String, Object> getLatestStatistics() {
		return latestStatistics;
	}

}
