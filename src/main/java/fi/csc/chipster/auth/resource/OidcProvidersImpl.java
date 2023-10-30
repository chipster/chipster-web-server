package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import jakarta.ws.rs.InternalServerErrorException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderConfigurationRequest;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;

public class OidcProvidersImpl implements OidcProviders {
	
	private static final Logger logger = LogManager.getLogger();
	
	private ArrayList<OidcConfig> sortedOidcConfigs = new ArrayList<>();	
	private HashMap<OidcConfig, IDTokenValidator> validators = new HashMap<>();	
	
	private HashMap<OidcConfig, URI> userInfoEndpointURIs = new HashMap<>();

	public OidcProvidersImpl(AuthTokens tokenTable, UserTable userTable, Config config) {
		ArrayList<OidcConfig> oidcConfigs = new ArrayList<>();
		
		for (String oidcName : config.getConfigEntries(CONF_ISSUER + "-").keySet()) {			

			OidcConfig oidc = OidcProviders.getOidcConfig(oidcName, config);
			
			logger.info("OIDC " + oidc.getOidcName() + " user ID claim: " + oidc.getClaimUserId());
			logger.info("OIDC " + oidc.getOidcName() + " user ID prefix: " + oidc.getUserIdPrefix());
			
			logRequiredClaim("OIDC " + oidc.getOidcName() + " required claim", oidc.getRequiredClaimKey(), oidc.getRequiredClaimValue(), oidc.getRequiredClaimValueComparison());
			logRequiredClaim("OIDC " + oidc.getOidcName() + " required userinfo claim", oidc.getRequiredUserinfoClaimKey(), oidc.getRequiredUserinfoClaimValue(), oidc.getRequiredUserinfoClaimValueComparison());
						 
			// use list, because there is no good map key. Multiple oidcConfigs may have the same issuer.
			oidcConfigs.add(oidc);
			
			OIDCProviderMetadata metadata;
			try {
				metadata = getMetadata(oidc);
			
				// if multiple oidConfigs have the same issuer, they must have the same clientId
				// because before validation we know only the issuer
				this.validators.put(oidc, createValidator(oidc.getIssuer(), oidc.getClientId(), metadata.getJWKSetURI(), null));			
				
				this.userInfoEndpointURIs.put(oidc, getUserInfoEndpointURI(oidc, metadata));
				
			} catch (com.nimbusds.oauth2.sdk.ParseException | IOException | URISyntaxException e) {
				throw new RuntimeException("oidc metadata error " + oidc.getOidcName(), e);
			}
		}
	
		setOidcConfigs(oidcConfigs);		
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

	protected void setOidcConfigs(ArrayList<OidcConfig> oidcConfigs) {
		
		sortedOidcConfigs = new ArrayList<OidcConfig>(oidcConfigs);
		sortedOidcConfigs.sort((a, b) -> a.getPriority().compareTo(b.getPriority()));
		
		for (OidcConfig oidc : sortedOidcConfigs) {			
			logger.info("OpenID Connect issuer " + oidc.getIssuer() + " enabled");
		}
	}

	private OIDCProviderMetadata getMetadata(OidcConfig oidc) throws IOException, com.nimbusds.oauth2.sdk.ParseException {
		
		// The OpenID provider issuer URL
		Issuer issuer = new Issuer(oidc.getIssuer());

		// Will resolve the OpenID provider metadata automatically
		OIDCProviderConfigurationRequest request = new OIDCProviderConfigurationRequest(issuer);

		// Make HTTP request
		HTTPRequest httpRequest = request.toHTTPRequest();
		HTTPResponse httpResponse = httpRequest.send();

		// Parse OpenID provider metadata
		return OIDCProviderMetadata.parse(httpResponse.getBodyAsJSONObject());
	}
	
	
	private URI getUserInfoEndpointURI(OidcConfig oidc, OIDCProviderMetadata metadata) {
		URI uri = metadata.getUserInfoEndpointURI();
		
		if (!oidc.getRequiredUserinfoClaimKey().isEmpty() && uri == null) {
			throw new IllegalStateException("OpenID Connect userinfo endpoint is null, cannot check required claims without it");
		}
		
		return uri;
	}

	public ArrayList<OidcConfig> getOidcConfigs() {
		
		return this.sortedOidcConfigs;
	}

	@Override
	public UserInfo getUserInfo(OidcConfig oidcConfig, String accessTokenString, boolean isDebug) {
		URI userInfoEndpoint = this.userInfoEndpointURIs.get(oidcConfig);
		BearerAccessToken token = new BearerAccessToken(accessTokenString);
		
		if (isDebug) {
			logger.info("get userinfo from " + userInfoEndpoint);
		}

		try {
			// Make the request
			HTTPResponse httpResponse = new UserInfoRequest(userInfoEndpoint, token)
			    .toHTTPRequest()
			    .send();

			// Parse the response
			UserInfoResponse userInfoResponse = UserInfoResponse.parse(httpResponse);
	
			if (! userInfoResponse.indicatesSuccess()) {
			    // The request failed, e.g. due to invalid or expired token
			    logger.error("userinfo request failed: " + 
			    		userInfoResponse.toErrorResponse().getErrorObject().getCode() + " " + 
			    		userInfoResponse.toErrorResponse().getErrorObject().getDescription());
			    
			    throw new InternalServerErrorException("userinfo request failed");
			}
			
			return userInfoResponse.toSuccessResponse().getUserInfo();
			
		} catch (com.nimbusds.oauth2.sdk.ParseException | IOException e) {
			throw new InternalServerErrorException("oidc userinfo error", e);
		}

	}

	public IDTokenValidator createValidator(String issuerString, String clientIdString, URI jwkSetURI, JWKSet jwkSet) throws URISyntaxException, IOException {
		
		if (jwkSetURI == null && jwkSet == null) {
			throw new IllegalStateException("OpenID Connect jwk_uri is null, cannot verify login tokens without it");
		} else {
			logger.info("download OpenID Connect keys from " + jwkSetURI);
		}
		
		Issuer issuer = new Issuer(issuerString);
		ClientID clientID = new ClientID(clientIdString);
		JWSAlgorithm algorithm = JWSAlgorithm.RS256;

		// Create validator for signed ID tokens
		if (jwkSetURI != null) {
			// it should download the token signing keys and keep them updated (e.g. daily for google) 
			return new IDTokenValidator(issuer, clientID, algorithm, jwkSetURI.toURL());
		} else {
			// give keys directly in tests
			return new IDTokenValidator(issuer, clientID, algorithm, jwkSet);
		}
	}

	@Override
	public IDTokenValidator getValidator(OidcConfig oidcConfig) {
		return validators.get(oidcConfig);
	}

}
