package fi.csc.chipster.auth;

import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.jax.rs.features.JaxRsConfigProvider;
import org.pac4j.jax.rs.pac4j.JaxRsAjaxRequestResolver;
import org.pac4j.jax.rs.pac4j.JaxRsUrlResolver;
import org.pac4j.oauth.client.FacebookClient;

@Provider
public class OpenIDClientFeature implements Feature {

	public OpenIDClientFeature() {

	}

	@Override
	public boolean configure(FeatureContext context) {
		context.register(new JaxRsConfigProvider(setOIDCClientInfo()));
		return true;
	}

	public Config setOIDCClientInfo() {
		String clientId = "899798317484-fj5l1hjehbcnoplb7b5hc7kqhbsdjj1l.apps.googleusercontent.com";
		String secret = "i_6nvu-3xbrFjWeZnb0yebtm";

		FacebookClient facebookClient = new FacebookClient("fbId", "fbSecret");
		facebookClient.setCallbackUrl("http://localhost:8080/Callback");

		// Google2Client

		Clients clients = new Clients("notUsedCallbackUrl", facebookClient);
		// in case of invalid credentials, we simply want the error, not a redirect to
		// the login url
		clients.setAjaxRequestResolver(new JaxRsAjaxRequestResolver());

		// so that callback url have the correct prefix w.r.t. the container's context
		clients.setUrlResolver(new JaxRsUrlResolver());

		Config conf = new Config("/callback", facebookClient);
		
		System.out.println(conf);

		return conf;

	}
	
}
