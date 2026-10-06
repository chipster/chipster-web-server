package fi.csc.chipster.sessionworker;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpStatus;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Rule;
import jakarta.annotation.security.RolesAllowed;
import jakarta.mail.MessagingException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@Path("support")
public class SupportResource {

    private static Logger logger = LogManager.getLogger();

    private SmtpEmails emails;
    private RequestThrottle requestThrottle;
    private Map<String, String> supportEmails;

    private AuthenticationClient authService;

    private SessionDbClient sessionDb;

    private String supportSessionOwner;

    private static final int MAX_EMAIL_BYTES = 100 * 1024;

    public SupportResource(Config config, AuthenticationClient authService, SessionDbClient sessionDb) {
        this.authService = authService;
        this.sessionDb = sessionDb;

        String host = config.getString(Config.KEY_SESSION_WORKER_SMTP_HOST);
        int port = config.getInt(Config.KEY_SESSION_WORKER_SMTP_PORT);
        String username = config.getString(Config.KEY_SESSION_WORKER_SMPT_USERNAME);
        String password = config.getString(Config.KEY_SESSION_WORKER_SMTP_PASSWORD);
        boolean tls = config.getBoolean(Config.KEY_SESSION_WORKER_SMTP_TLS);
        boolean auth = config.getBoolean(Config.KEY_SESSION_WORKER_SMTP_AUTH);
        String from = config.getString(Config.KEY_SESSION_WORKER_SMTP_FROM);
        String fromName = config.getString(Config.KEY_SESSION_WORKER_SMTP_FROM_NAME);

        this.emails = new SmtpEmails(host, port, username, password, tls, auth, from, fromName);

        int throttleMinutes = config.getInt(Config.KEY_SESSION_WORKER_SUPPORT_THROTTLE_PERIOD);
        int throttleRequestCount = config.getInt(Config.KEY_SESSION_WORKER_SUPPORT_THROTTLE_REQEUST_COUNT);

        this.requestThrottle = new RequestThrottle(Duration.ofMinutes(throttleMinutes), throttleRequestCount);

        this.supportEmails = config.getSupportEmails();
        this.supportSessionOwner = config.getString(Config.KEY_SESSION_WORKER_SUPPORT_SESSION_OWNER);

        for (String app : this.supportEmails.keySet()) {
            logger.info("app " + app + " is configured to send support emails to " + this.supportEmails.get(app));
        }
    }

    @POST
    @Path("request")
    @RolesAllowed(Role.CLIENT)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(SupportRequest feedback, @Context SecurityContext sc) throws RestException {

        String userId = sc.getUserPrincipal().getName();

        // protect email infrastructure from attacks
        Duration retryAfter = requestThrottle.throttle(userId);
        if (retryAfter.isZero()) {

            User user = authService.getUser(new UserId(userId));

            logger.info("got feedback from: " + user.getUserId().toUserIdString());

            String emailBody = getEmailBody(feedback, user);
            String emailSubject = getEmailSubject(feedback, user);
            String emailReplyTo = getEmailReplyTo(feedback, user);

            // no reply-to address when neither the request nor the authentication has one
            int replyToLength = emailReplyTo != null ? emailReplyTo.length() : 0;

            if (emailBody.length() + emailSubject.length() + replyToLength > MAX_EMAIL_BYTES) {
                logger.warn(
                        "support request from " + user.getUserId().toUserIdString() + " rejected: payload too large");
                return Response.status(HttpStatus.PAYLOAD_TOO_LARGE_413).build();
            }

            // check the session before sending the email, so that a refused request
            // doesn't send one
            SupportCopy supportCopy = null;
            if (feedback.getSession() != null) {
                try {
                    supportCopy = getSupportCopy(feedback.getSession(), userId);
                } catch (RestException e) {
                    logger.error("checking the session of the support request failed", e);
                }
            }

            // allow different support addresses to be configured for different apps
            String supportEmail;
            if (this.supportEmails.containsKey(feedback.getApp())) {
                supportEmail = this.supportEmails.get(feedback.getApp());
            } else {
                // client didn't set the app or it there was no configuration for it, use the
                // default
                supportEmail = this.supportEmails.get("chipster");
            }

            if (supportEmail != null && !supportEmail.isEmpty()) {
                try {
                    this.emails.send(emailSubject, emailBody, supportEmail, emailReplyTo);

                } catch (UnsupportedEncodingException | MessagingException e) {
                    throw new InternalServerErrorException("sending support email failed", e);
                }
            } else {
                // log the message to make the development easier
                logger.warn("support-email is not configured, the feedback will be logged");
                logger.info("Subject: " + emailSubject);
                logger.info("Reply-To: " + emailReplyTo);
                logger.info("Body: \n" + emailBody);
            }

            // hide the copy from the user only after the email was sent. If the email
            // failed, the user still sees the copy and can send the request again. If
            // this fails, the user still sees the copy and the support account still has
            // its share, pending or accepted, from the link in the email.
            if (supportCopy != null) {
                try {
                    acceptShare(supportCopy);
                } catch (RestException e) {
                    logger.error("accepting session share failed", e);
                }
            }

            return Response.noContent().build();

        } else {
            // + 1 to round up
            long ceilSeconds = retryAfter.getSeconds() + 1;
            return Response
                    .status(HttpStatus.TOO_MANY_REQUESTS_429)
                    .header(RequestThrottle.HEADER_RETRY_AFTER, ceilSeconds)
                    .build();
        }
    }

    /**
     * The two rules of the session copy of a support request
     * 
     * @param sessionId the session copy
     * @param userRule  the user's own read-write rule
     * @param shareRule the user's read-write share to the support account
     */
    private record SupportCopy(UUID sessionId, Rule userRule, Rule shareRule) {
    }

    /**
     * Find the rules of the session copy of a support request
     * 
     * The client copies the user's session and shares the copy to the support
     * account. Accept only a session that has exactly these two rules, because the
     * copy isn't marked in any other way and other users' access to any other
     * session must not be changed.
     * 
     * @throws BadRequestException if the url doesn't end in a session id
     * @throws ForbiddenException  if the session has other rules
     */
    private SupportCopy getSupportCopy(String sessionUrl, String userId) throws RestException {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(RestUtils.basename(sessionUrl));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid session url");
        }

        // only the rules, getSession() would update the accessed time of the session
        List<Rule> rules = sessionDb.getRules(sessionId);

        if (rules == null || rules.size() != 2) {
            throw new ForbiddenException("session access denied");
        }

        // the user's read-write rule, not a pending share
        Rule userRule = rules.stream()
                .filter(r -> userId.equals(r.getUsername()))
                .filter(r -> r.isReadWrite())
                .filter(r -> r.getSharedBy() == null)
                .findAny()
                .orElseThrow(() -> new ForbiddenException("session access denied"));

        // read-write share from the user to the support account
        Rule shareRule = rules.stream()
                .filter(r -> this.supportSessionOwner.equals(r.getUsername()))
                .filter(r -> r.isReadWrite())
                .filter(r -> userId.equals(r.getSharedBy()))
                .findAny()
                .orElseThrow(() -> new ForbiddenException("session not shared to support"));

        return new SupportCopy(sessionId, userRule, shareRule);
    }

    /**
     * Give the session copy of a support request to the support account
     * 
     * Accept the share to the support account and delete the user's own rule to
     * hide the copy from the user.
     */
    private void acceptShare(SupportCopy copy) throws RestException {
        copy.shareRule().setSharedBy(null);
        sessionDb.updateRule(copy.sessionId(), copy.shareRule());

        // hide the copy from the user
        sessionDb.deleteRule(copy.sessionId(), copy.userRule().getRuleId());
    }

    private String getEmailBody(SupportRequest feedback, User user) {
        String userIdString = user.getUserId().toUserIdString();

        String sessionUrl = feedback.getSession();
        if (sessionUrl == null) {
            sessionUrl = "[not available]";
        }

        String emailBody = "userId: " + userIdString + "\n";

        if (user.getName() != null) {
            emailBody += "name: " + user.getName() + "\n";
        } else {
            emailBody += "name: [not available]\n";
        }

        if (user.getOrganization() != null) {
            emailBody += "organization: " + user.getOrganization() + "\n";
        } else {
            emailBody += "organization: [not available]\n";
        }

        emailBody += "session: " + sessionUrl + "\n";

        if (user.getMail() != null) {
            emailBody += "email (from authentication): " + user.getMail() + "\n";
        }

        // show the email given by user if it's different
        if (feedback.getMail() != null && !feedback.getMail().equals(user.getMail())) {
            emailBody += "email (given by user): " + feedback.getMail() + "\n";
        }

        if (user.getMail() == null && feedback.getMail() == null) {
            emailBody += "email: [not available]\n";
        }

        emailBody += "\n";

        emailBody += "message: \n" + feedback.getMessage() + "\n\n";

        emailBody += "log: \n";
        emailBody += feedback.getLog() + "\n\n";

        return emailBody;
    }

    private String getEmailReplyTo(SupportRequest feedback, User user) {

        if (feedback.getMail() != null) {
            return feedback.getMail();
        }

        return user.getMail();
    }

    private String getEmailSubject(SupportRequest feedback, User user) {

        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String dateString = dateFormat.format(new Date());

        String subject = dateString + " Help request from " + user.getUserId().toUserIdString();

        return subject;
    }
}
