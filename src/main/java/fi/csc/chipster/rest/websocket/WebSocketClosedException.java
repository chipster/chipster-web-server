package fi.csc.chipster.rest.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCode;

public class WebSocketClosedException extends Exception {

	private CloseReason closeReason;

	public WebSocketClosedException(CloseCode closeCode, String reasonPhrase) {
		this(new CloseReason(closeCode, reasonPhrase));
	}
	
	public WebSocketClosedException(CloseReason closeReason) {
		super(closeReason.getCloseCode() + " " + closeReason.getReasonPhrase());
		
		this.closeReason = closeReason;
	}

	public CloseReason getCloseReason() {
		return closeReason;
	}			
}