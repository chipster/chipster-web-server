package fi.csc.chipster.sessionworker;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class SmtpEmails {
	
	private String host;
	private String username;
	private String password;
	private boolean tls;
	private boolean auth;
	private String from;
	private String fromName;
	private int port;

	public SmtpEmails(String host, int port, String username, String password, boolean tls, boolean auth, String from,
			String fromName) {
		
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.tls = tls;
		this.auth = auth;
		this.from = from;
		this.fromName = fromName;
	}

	public void send(String subject, String body, String to, String replyTo) throws MessagingException, UnsupportedEncodingException {

    	Properties props = System.getProperties();
    	props.put("mail.transport.protocol", "smtp");
    	props.put("mail.smtp.port", this.port); 
    	props.put("mail.smtp.starttls.enable", new Boolean(this.tls).toString());
    	props.put("mail.smtp.auth", new Boolean(this.auth).toString());
 
    	Session session = Session.getDefaultInstance(props);
 
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(this.from, this.fromName));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject, StandardCharsets.UTF_8.name());
        msg.setText(body, StandardCharsets.UTF_8.name());
        
        if (replyTo != null) {
            msg.addHeader("Reply-To", replyTo);
        }
            
        Transport transport = session.getTransport();
                    
        try {
            transport.connect(this.host, this.username, this.password);        
            transport.sendMessage(msg, msg.getAllRecipients());
            System.out.println("Email sent!");
        } finally {
            transport.close();
        }
	}
}
