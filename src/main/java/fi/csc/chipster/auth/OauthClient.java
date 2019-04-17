package fi.csc.chipster.auth;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.oauth2.Oauth2;
import com.google.api.services.oauth2.model.Tokeninfo;
import com.google.api.services.oauth2.model.Userinfoplus;

public class OauthClient {

	// Specify the name of your application

	private static final String APPLICATION_NAME = "";

	// Directory to store user credentials
	private static final java.io.File DATA_STORE_DIR = new java.io.File(System.getProperty("user.home"),
			".store/oauth_client");

	private static HttpTransport httpTransport;

	/* Global instance of the {@link DataStoreFactory} */

	private static FileDataStoreFactory dataStoreFactory;

	/* Global Instance for JSON factory */
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

	/* Oauth 2.0 scopes */
	private static final List<String> SCOPES = Arrays.asList("https://www.googleapis.com/auth/userinfo.profile",
			"https://www.googleapis.com/auth/userinfo.email");

	private static Oauth2 oauth2;
	private static GoogleClientSecrets clientSecrets;

	/* Authorize the installed application to access user's protected data */
	private static Credential authoroize() throws Exception {

		// load client secret
		clientSecrets = GoogleClientSecrets.load(JSON_FACTORY,
				new InputStreamReader(OauthClient.class.getResourceAsStream("/client_secrets.json")));
		System.out.println(clientSecrets);

		// set the authorization code flow
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY,
				clientSecrets, SCOPES).setDataStoreFactory(dataStoreFactory).build();

		// authorize
		//

		return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

	}

	public void createRequest() throws IOException {
		try {
			System.out.println("creating oauth Req");
			httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			dataStoreFactory = new FileDataStoreFactory(DATA_STORE_DIR);
			// authorization
			Credential credential = authoroize();
			// Set up global Oauth2 instance
			oauth2 = new Oauth2.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME)
					.build();
			tokenInfo(credential.getAccessToken());
			userInfo();
			return;
		} catch (Throwable t) {

		}
	}

	private static void tokenInfo(String accessToken) throws IOException {
		header("Validating a token");
		Tokeninfo tokeninfo = oauth2.tokeninfo().setAccessToken(accessToken).execute();
		System.out.println(tokeninfo.toPrettyString());
		if (!tokeninfo.getAudience().equals(clientSecrets.getDetails().getClientId())) {
			System.err.println("ERROR: audience does not match our client ID!");
		}
	}

	private static void userInfo() throws IOException {
		header("Obtaining User Profile Information");
		Userinfoplus userinfo = oauth2.userinfo().get().execute();
		// here we can check the userinfo to check with our database whether it is an
		// existing user or new user and redirect according to it
		System.out.println(userinfo.toPrettyString());
	}

	static void header(String name) {
		System.out.println();
		System.out.println("================== " + name + " ==================");
		System.out.println();
	}

}
