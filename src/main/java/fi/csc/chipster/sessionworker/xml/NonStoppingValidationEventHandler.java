package fi.csc.chipster.sessionworker.xml;

import java.util.LinkedList;
import java.util.List;

import jakarta.xml.bind.ValidationEvent;
import jakarta.xml.bind.ValidationEventHandler;

public class NonStoppingValidationEventHandler implements ValidationEventHandler {
	
	private List<ValidationEvent> validationEvents = new LinkedList<ValidationEvent>();
	
	/**
	 * Continue, no matter what.
	 */
	@Override
	public boolean handleEvent(ValidationEvent event) {
		this.validationEvents.add(event);
		return true;
	}
	
	public boolean hasEvents() {
		return validationEvents.size() > 0;
	}

	public String getValidationEventsAsString() {
		String s = "";
		for (ValidationEvent event : validationEvents) {
			s += event.getMessage() + "\n";
		}
		return s;
	}
}
