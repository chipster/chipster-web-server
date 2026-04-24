package fi.csc.chipster.auth.oidc;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderConfigurationRequest;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;

import fi.csc.chipster.auth.resource.AuthTokens;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.auth.resource.UserTable;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import jakarta.ws.rs.InternalServerErrorException;

/**
 * OidcProvders implementation
 * 
 * This class contains code that is needed to setup the OidcProviders correctly
 * for real use, but which isn't tested, either because it's trivial or because
 * it's difficult to test (for example due to external connections). Security
 * critical code should be directly in the class OidcProviders and be tested.
 */
public class OidcProvidersImpl extends OidcProviders {

	private static final Logger logger = LogManager.getLogger();

	// use List to keep the order for the app
	private ArrayList<OidcConfig> publicOidcConfigs = new ArrayList<>();

	private HashMap<String, OIDCProviderMetadata> metadatas = new HashMap<>();

	public OidcProvidersImpl(AuthTokens tokenTable, UserTable userTable, Config config) throws MalformedURLException {

		for (String oidcName : config.getConfigEntries(CONF_ISSUER + "-").keySet()) {

			OidcConfig oidc = OidcProviders.getOidcConfig(oidcName, config);

			logger.info("OIDC " + oidc.getOidcName() + " user ID claim: " + oidc.getClaimUserId());
			logger.info("OIDC " + oidc.getOidcName() + " user ID prefix: " + oidc.getUserIdPrefix());

			logRequiredClaim("OIDC " + oidc.getOidcName() + " required claim", oidc.getRequiredClaimKey(),
					oidc.getRequiredClaimValue(), oidc.getRequiredClaimValueComparison());
			logger.info("OIDC " + oidc.getOidcName() + " query userinfo: " + oidc.getQueryUserInfo());

			OIDCProviderMetadata metadata = loadMetadata(oidc);

			this.metadatas.put(oidc.getOidcName(), metadata);

			super.addOidcConfig(oidc, metadata.getJWKSetURI(), null);

			logger.info("OIDC " + oidc.getOidcName() + " enabled");

		}

		ArrayList<OidcConfig> sortedOidcConfigs = new ArrayList<OidcConfig>(getOidcConfigs().values());
		sortedOidcConfigs.sort((a, b) -> a.getPriority().compareTo(b.getPriority()));

		for (OidcConfig privateConfig : sortedOidcConfigs) {

			OidcConfig publicOidcConfig = new OidcConfig();

			// at least client secret must be removed
			// but let's keep only information that is needed in the app
			publicOidcConfig.setOidcName(privateConfig.getOidcName());
			publicOidcConfig.setDescription(privateConfig.getDescription());
			publicOidcConfig.setLogo(privateConfig.getLogo());
			publicOidcConfig.setText(privateConfig.getText());

			publicOidcConfigs.add(publicOidcConfig);
		}
	}

	private void logRequiredClaim(String name, String key, String value,
			String comparison) {

		if (key.isEmpty()) {
			logger.info(name + ": none");
			return;
		}

		if (OidcResource.COMPARISON_STRING.equals(comparison)) {
			logger.info(name + " " + key + "=" + value);
		} else if (OidcResource.COMPARISON_JSON_ARRAY_ANY.equals(comparison)) {

			logger.info(name + " " + key + " must include any of values: ");

			@SuppressWarnings("unchecked")
			HashSet<String> items = RestUtils.parseJson(HashSet.class, value);
			for (String item : items) {
				logger.info("- " + item);
			}
		} else if (OidcResource.COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {
			logger.info(name + " " + key + " must include all values: ");

			@SuppressWarnings("unchecked")
			HashSet<String> items = RestUtils.parseJson(HashSet.class, value);
			for (String item : items) {
				logger.info("- " + item);
			}
		} else {
			logger.error("unknown " + name + " comparison: " + comparison);
		}

	}

	public static OIDCProviderMetadata loadMetadata(OidcConfig oidc) {

		// The OpenID provider issuer URL
		Issuer issuer = new Issuer(oidc.getIssuer());

		// Will resolve the OpenID provider metadata automatically
		OIDCProviderConfigurationRequest request = new OIDCProviderConfigurationRequest(issuer);

		// Make HTTP request
		HTTPRequest httpRequest = request.toHTTPRequest();
		logger.info("OIDC " + oidc.getOidcName() + " get metadata from " + httpRequest.getURI());
		HTTPResponse httpResponse;
		try {
			httpResponse = httpRequest.send();
			// Parse OpenID provider metadata
			return OIDCProviderMetadata.parse(httpResponse.getBodyAsJSONObject());

		} catch (IOException e) {
			throw new InternalServerErrorException("failed to get OIDC metadata", e);
		} catch (ParseException e) {
			throw new InternalServerErrorException("failed to parse OIDC metadata", e);
		}

	}

	public ArrayList<OidcConfig> getPublicOidcConfigs() {

		return this.publicOidcConfigs;
	}

	@Override
	public UserInfo getUserInfo(AccessToken accessToken, String oidcName) {

		return NimbusHelpers.userInfo(accessToken, this.metadatas.get(oidcName));
	}

	@Override
	public String getAuthorizationEndpointURI(String oidcName) {
		return this.metadatas.get(oidcName).getAuthorizationEndpointURI().toString();
	}

	@Override
	public URI getTokenEndpoint(String oidcName) {
		return this.metadatas.get(oidcName).getTokenEndpointURI();
	}
}
