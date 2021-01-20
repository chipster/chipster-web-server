package fi.csc.chipster.rest.websocket;

public class WebSocketErrorException extends Exception {

	public WebSocketErrorException(Throwable throwable) {
		super(throwable);
	}
}