package fi.csc.chipster.auth.resource;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest.Builder;
import com.nimbusds.openid.connect.sdk.AuthenticationResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponseParser;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.Transaction;
import io.jsonwebtoken.lang.Arrays;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Rest endpoint for logging in to Chipster with OpenID Connect
 * 
 * At the moment the OIDC client runs in a browser, so here we only receive the
 * id_token,
 * validate it and create a new Chipster token. Optionally also a OIDC userinfo
 * endpoint is
 * queried and its claim checked.
 * 
 * Running OIDC client in the browser allows the token to be stored directly in
 * the local storage of
 * the main Chipster app. If OIDC return URL pointed to the server, it would be
 * difficult
 * to transfer the token to the main app. Ideas:
 * - Save the to the local storage. Doesn't work if the main app (web-server)
 * and the OIDC authentication handler
 * (auth) are served from different domains.
 * - Respond with a html having iframe which is loaded from the web-server,
 * which stores the token to
 * the local storage. Worked in Chrome and Firefox, but not in Safari, if I
 * remember correctly. Safari
 * uses the page domain hierarchy to keep local storages separated.
 * - Pass the token in the query parameter of redirect URL. Should work, but
 * insecure?
 * 
 * @author klemela
 */
@Path("oidc")
public class OidcResource {

	public static final String COMPARISON_STRING = "string";
	public static final String COMPARISON_JSON_ARRAY_ALL = "jsonArrayAll";
	public static final String COMPARISON_JSON_ARRAY_ANY = "jsonArrayAny";

	private static final Logger logger = LogManager.getLogger();

	public static final String CONF_DEBUG = "auth-oidc-debug";

	private AuthTokens authTokens;
	private UserTable userTable;

	private boolean isDebug;

	private OidcProviders oidcProviders;

	public OidcResource(OidcProviders oidcProviders) {
		this.oidcProviders = oidcProviders;
	}

	public void init(AuthTokens authTokens, UserTable userTable, Config config) throws URISyntaxException, IOException {
		this.authTokens = authTokens;
		this.userTable = userTable;
		this.isDebug = config.getBoolean(CONF_DEBUG);
	}

	@GET
	@Path("callback")
	@RolesAllowed({ Role.UNAUTHENTICATED, Role.WEB_SERVER })
	@Produces(MediaType.TEXT_PLAIN)
	@Transaction
	public Response oidcCallback(@Context UriInfo uriInfo)
			throws GeneralSecurityException, IOException, ParseException {

		logger.debug("oidc callback " + uriInfo.getRequestUri().toASCIIString());

		// request uri, including query parameters
		AuthorizationCode code = this.authentication(URI.create(uriInfo.getRequestUri().toASCIIString()));

		// FIXME how to know which oidcConfig?
		OidcConfig oidcConfig = this.getOidcConfig("haka-code");

		OIDCProviderMetadata metadata = OidcProvidersImpl.getMetadata(oidcConfig);

		OIDCTokenResponse tokenResponse = this.tokenRequest(oidcConfig, code, metadata);

		// Get the ID and access token, the server may also return a refresh token
		JWT idToken = tokenResponse.getOIDCTokens().getIDToken();
		AccessToken accessToken = tokenResponse.getOIDCTokens().getAccessToken();
		// RefreshToken refreshToken = tokenResponse.getOIDCTokens().getRefreshToken();

		if (idToken == null) {
			throw new ForbiddenException("no ID token");
		}

		IDTokenClaimsSet claims = validate(idToken, oidcConfig, metadata);

		// token is valid, we can trust that it came from the issuer

		if (this.isDebug) {
			logger.info("claims after validation: ");
			// there is no easier way to iterate claims?
			Map<String, Object> jwtClaims;
			try {
				jwtClaims = claims.toJWTClaimsSet().getClaims();
				for (String k : jwtClaims.keySet()) {
					logger.info("claim " + k + ": " + jwtClaims.get(k));
				}
			} catch (com.nimbusds.oauth2.sdk.ParseException e) {
				logger.error("failed to print claims", e);
			}
		}

		UserInfo userInfo = null;

		if (oidcConfig.getRequiredUserinfoClaimKey().isEmpty()) {
			if (this.isDebug) {
				logger.info("no required userinfo claims");
			}
		} else {
			userInfo = userInfo(oidcConfig, accessToken, metadata);
			checkUserInfo(oidcConfig, userInfo, claims.getSubject().toString());
		}

		String chipsterToken = getChipsterToken(claims, userInfo, oidcConfig);

		return Response.ok(chipsterToken).build();
	}

	/*
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
	 * openid-connect/oidc-auth
	 */
	private AuthorizationCode authentication(URI uri) {
		// parse code and state from request
		AuthenticationResponse response;
		try {
			response = AuthenticationResponseParser.parse(uri);
		} catch (com.nimbusds.oauth2.sdk.ParseException e) {
			throw new BadRequestException("failed to parse request uri");
		}

		// Check the state
		// FIXME store in db?
		// if (!response.getState().equals(state)) {
		// System.err.println("Unexpected authentication response");
		// return;
		// }

		if (response instanceof AuthenticationErrorResponse) {
			// The OpenID provider returned an error
			// TODO test
			throw new InternalServerErrorException(
					"error in OIDC callback " + RestUtils.asJson(response.toErrorResponse().getErrorObject()));
		}

		// Retrieve the authorisation code, to use it later at the token endpoint
		AuthorizationCode code = response.toSuccessResponse().getAuthorizationCode();

		return code;
	}

	/*
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
	 * openid-connect/token-request
	 */
	private OIDCTokenResponse tokenRequest(OidcConfig oidcConfig, AuthorizationCode code,
			OIDCProviderMetadata metadata) {
		// Construct the code grant from the code obtained from the authz endpoint
		// and the original callback URI used at the authz endpoint
		URI callback;
		try {
			callback = new URI(oidcConfig.getRedirectPath());
		} catch (URISyntaxException e) {
			throw new InternalServerErrorException("failed to parse callback path", e);
		}
		AuthorizationGrant codeGrant = new AuthorizationCodeGrant(code, callback);

		// The credentials to authenticate the client at the token endpoint
		ClientID clientID = new ClientID(oidcConfig.getClientId());
		Secret clientSecret = new Secret(oidcConfig.getClientSecret());
		ClientAuthentication clientAuth = new ClientSecretBasic(clientID, clientSecret);

		// The token endpoint
		URI tokenEndpoint = metadata.getTokenEndpointURI();

		// Make the token request
		TokenRequest request = new TokenRequest(tokenEndpoint, clientAuth, codeGrant);

		TokenResponse tokenResponse;
		try {
			tokenResponse = OIDCTokenResponseParser.parse(request.toHTTPRequest().send());
		} catch (com.nimbusds.oauth2.sdk.ParseException e) {
			throw new InternalServerErrorException("failed to parse id_token response", e);
		} catch (IOException e) {
			throw new InternalServerErrorException("failed to get id_token", e);
		}

		if (!tokenResponse.indicatesSuccess()) {
			// We got an error response...
			TokenErrorResponse errorResponse = tokenResponse.toErrorResponse();
			ErrorObject err = errorResponse.getErrorObject();
			throw new InternalServerErrorException(
					"failed to get id_token " + err.getCode() + " " + err.getDescription());
		}

		OIDCTokenResponse successResponse = (OIDCTokenResponse) tokenResponse.toSuccessResponse();

		return successResponse;
	}

	/*
	 * https://connect2id.com/blog/how-to-validate-an-openid-connect-id-token
	 */
	private IDTokenClaimsSet validate(JWT idToken, OidcConfig oidcConfig, OIDCProviderMetadata metadata)
			throws MalformedURLException {
		// The required parameters
		Issuer iss = new Issuer(oidcConfig.getIssuer());
		ClientID clientID = new ClientID(oidcConfig.getClientId());
		// FIXME make configurable
		JWSAlgorithm jwsAlg = JWSAlgorithm.RS256;
		URI jwkSetURL = metadata.getJWKSetURI();

		// Create validator for signed ID tokens
		IDTokenValidator validator = new IDTokenValidator(iss, clientID, jwsAlg, jwkSetURL.toURL());

		// Set the expected nonce, leave null if none
		// FIXME
		// Nonce expectedNonce = new Nonce("xyz..."); // or null
		Nonce expectedNonce = null;

		IDTokenClaimsSet claims;

		try {
			claims = validator.validate(idToken, expectedNonce);
		} catch (BadJOSEException e) {
			// Invalid signature or claims (iss, aud, exp...)
			throw new InternalServerErrorException("id_token validation failed", e);
		} catch (JOSEException e) {
			// Internal processing exception
			throw new InternalServerErrorException("id_token validation failed", e);
		}

		return claims;
	}

	/*
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
	 * openid-connect/userinfo
	 */
	private UserInfo userInfo(OidcConfig oidcConfig, AccessToken accessToken, OIDCProviderMetadata metadata) {
		URI userInfoEndpoint = metadata.getUserInfoEndpointURI();

		// Make the request
		HTTPResponse httpResponse;
		try {
			httpResponse = new UserInfoRequest(userInfoEndpoint, accessToken)
					.toHTTPRequest()
					.send();
		} catch (IOException e) {
			throw new InternalServerErrorException("failed to get userInfo", e);
		}

		// Parse the response
		UserInfoResponse userInfoResponse;
		try {
			userInfoResponse = UserInfoResponse.parse(httpResponse);
		} catch (com.nimbusds.oauth2.sdk.ParseException e) {
			throw new InternalServerErrorException("failed to parse userInfo response");
		}

		if (!userInfoResponse.indicatesSuccess()) {
			ErrorObject err = userInfoResponse.toErrorResponse().getErrorObject();
			// The request failed, e.g. due to invalid or expired token
			throw new InternalServerErrorException(
					"userInfo request failed: " + err.getCode() + " " + err.getDescription());
		}

		// Extract the claims
		return userInfoResponse.toSuccessResponse().getUserInfo();
	}

	// @POST
	// @RolesAllowed(Role.UNAUTHENTICATED)
	// @Consumes(MediaType.APPLICATION_JSON)
	// @Produces(MediaType.TEXT_PLAIN)
	// @Transaction

	private String getChipsterToken(IDTokenClaimsSet claims, UserInfo userInfo, OidcConfig oidcConfig) {

		String sub = claims.getSubject().getValue();

		String name = claims.getStringClaim("name");
		String email = claims.getStringClaim("email");
		Boolean emailVerified = claims.getBooleanClaim("email_verified");

		// use different auth names in Chipster based on the claims that we get
		String username = getClaim(oidcConfig.getClaimUserId(), claims);
		String userIdPrefix = oidcConfig.getUserIdPrefix();

		UserId userId = null;
		if (oidcConfig.getClaimUserId().equals("sub")) {
			userId = new UserId(userIdPrefix, sub);

		} else if (username != null) {
			userId = new UserId(userIdPrefix, username);

		} else {
			throw new ForbiddenException("username not found from claim " + oidcConfig.getClaimUserId());
		}

		// store only verified emails
		if (oidcConfig.getVerifiedEmailOnly() && (emailVerified == null || emailVerified == false)) {
			email = null;
		}

		String organization = getClaim(oidcConfig.getClaimOrganization(), claims);

		userTable.addOrUpdateFromLogin(userId, email, organization, name);

		HashSet<String> roles = Stream.of(Role.CLIENT, Role.OIDC).collect(Collectors.toCollection(HashSet::new));
		String token = authTokens.createNewUserToken(userId.toUserIdString(), roles, name);

		return token;
	}

	// /**
	// * Do this in separate method to allow it to be tested without setting up an
	// * OIDC xserver
	// *
	// * @param idToken
	// * @param accessTokenString
	// * @param validator
	// * @return
	// */
	// protected IDTokenClaimsSet validateIdToken(JWT idToken, IDTokenValidator
	// validator) {

	// IDTokenClaimsSet claims;
	// try {
	// // client can send anything
	// // we can trust this only after the signature, iss, sub, aud and ext are
	// checked
	// claims = validator.validate(idToken, null);

	// } catch (BadJOSEException e) {
	// // Invalid signature or claims (iss, aud, exp...)
	// logger.warn("invalid ID token", e);
	// throw new ForbiddenException("invalid ID token signature or claims");
	// } catch (JOSEException e) {
	// // Internal processing exception
	// logger.warn("invalid ID token", e);
	// throw new ForbiddenException("invalid ID token");
	// }

	// // token is valid, we can trust that it came from the issuer

	// return claims;
	// }

	private void checkUserInfo(OidcConfig oidcConfig, UserInfo userInfo, String sub) {

		Map<String, Object> jwtClaims;
		try {
			jwtClaims = userInfo.toJWTClaimsSet().getClaims();
		} catch (com.nimbusds.oauth2.sdk.ParseException e) {
			throw new InternalServerErrorException("failed to parse userInfo claims", e);
		}

		if (this.isDebug) {
			logger.info("claims from userinfo endpoint: ");
			for (String k : jwtClaims.keySet()) {
				logger.info("claim " + k + ": " + jwtClaims.get(k));
			}
		}

		// sanity checks, compare the sub claim from id_token and userinfo claims

		if (!userInfo.getSubject().getValue().equals(sub)) {
			throw new InternalServerErrorException("id_token and userinfo subjects differ");
		}

		if (!hasRequiredClaim(
				oidcConfig.getOidcName(),
				oidcConfig.getRequiredUserinfoClaimKey(),
				oidcConfig.getRequiredUserinfoClaimValue(),
				oidcConfig.getRequiredUserinfoClaimValueComparison(),
				jwtClaims)) {
			if (this.isDebug) {
				logger.info("access denied. Required userinfo claim not found: "
						+ oidcConfig.getRequiredUserinfoClaimKey());
			}
			throw new ForbiddenException(oidcConfig.getRequiredUserinfoClaimError());
		}
	}

	// /**
	// * Get the oidc config
	// *
	// * If the same issuer has multiple configs, iterate in priority order.
	// *
	// * @param issuer
	// * @param claims
	// * @return
	// */
	// protected OidcConfig getOidcConfig(String issuer, String clientId,
	// Map<String, Object> claims) {
	// if (this.isDebug) {
	// logger.info("searching oidc config");
	// }
	// for (OidcConfig oidc : oidcProviders.getOidcConfigs()) {
	// if (this.isDebug) {
	// logger.info("check if oidc config " + oidc.getOidcName() + " is suitable");
	// }
	// if (!oidc.getIssuer().equals(issuer)) {
	// if (this.isDebug) {
	// logger.info("issuer '" + issuer + "' does not match " + oidc.getIssuer() +
	// "'");
	// }
	// continue;
	// }

	// if (!oidc.getClientId().equals(clientId)) {
	// if (this.isDebug) {
	// logger.info("clientId '" + clientId + "' does not match " +
	// oidc.getClientId() + "'");
	// }
	// continue;
	// }

	// if (claims.get(oidc.getClaimUserId()) == null) {
	// if (this.isDebug) {
	// logger.info("claim '" + oidc.getClaimUserId() + "' not found");
	// }
	// continue;
	// }

	// if (!hasRequiredClaim(
	// oidc.getOidcName(),
	// oidc.getRequiredClaimKey(),
	// oidc.getRequiredClaimValue(),
	// oidc.getRequiredClaimValueComparison(),
	// claims)) {
	// continue;
	// }

	// if (this.isDebug) {
	// logger.info("oidc config found: " + oidc.getOidcName());
	// }
	// // this is fine
	// return oidc;
	// }
	// if (this.isDebug) {
	// logger.info("oidc config not found");
	// }
	// throw new ForbiddenException("oidc config not found for issuer " + issuer);
	// }

	protected boolean hasRequiredClaim(String oidcName, String requiredClaimKey, String requiredClaimValue,
			String comparison, Map<String, Object> claims) {
		if (!requiredClaimKey.isEmpty()) {

			Object claimObj = claims.get(requiredClaimKey);
			if (claimObj == null) {
				logger.info("oidc " + oidcName + " requires a non existent claim " + requiredClaimKey);
				return false;
			}

			if (!requiredClaimValue.isEmpty()) {
				String claimValue = claimObj.toString();

				if (COMPARISON_STRING.equals(comparison)) {
					if (!claimValue.equals(requiredClaimValue)) {
						if (this.isDebug) {
							logger.info("claim " + requiredClaimKey + " has value '" + claimValue
									+ "', which does not match expected '" + requiredClaimValue + "'");
						}
						return false;
					}
				} else if (COMPARISON_JSON_ARRAY_ANY.equals(comparison)
						|| COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {

					try {
						@SuppressWarnings("unchecked")
						HashSet<String> requiredValues = RestUtils.parseJson(HashSet.class, requiredClaimValue);
						@SuppressWarnings("unchecked")
						HashSet<String> usersValues = RestUtils.parseJson(HashSet.class, claimValue);

						if (COMPARISON_JSON_ARRAY_ANY.equals(comparison)) {

							if (!requiredValues.stream().anyMatch(usersValues::contains)) {
								if (this.isDebug) {
									logger.info("claim " + requiredClaimKey + " has value '" + claimValue
											+ "', which does not contain any of expected '" + requiredClaimValue + "'");
								}
								return false;
							}

						} else if (COMPARISON_JSON_ARRAY_ALL.equals(comparison)) {
							if (!requiredValues.stream().allMatch(usersValues::contains)) {
								if (this.isDebug) {
									logger.info("claim " + requiredClaimKey + " has value '" + claimValue
											+ "', which does not contain all of expected '" + requiredClaimValue + "'");
								}
								return false;
							}
						} else {
							logger.error("impossible");
							return false;
						}

					} catch (InternalServerErrorException e) {
						logger.error("failed to parse required claim value as json. configured: " + requiredClaimValue
								+ ", from oidc: " + claimValue);
						return false;
					}

				} else {
					logger.error("oidc " + oidcName + ", unknown required claim comparison: " + comparison);
					return false;
				}
			} else {
				if (isDebug) {
					logger.info("claim " + requiredClaimKey + " found, value is not required");
				}
			}

			// a claims is required and it's found
			return true;
		}
		// no claim is required, anything goes
		return true;
	}

	/**
	 * Get claim value
	 * 
	 * Return null if the claimName is an empty string (default in Chipster config).
	 * 
	 * @param claimName
	 * @param claims
	 * @return
	 */
	private String getClaim(String claimName, IDTokenClaimsSet claims) {
		if (!claimName.isEmpty()) {
			return claims.getStringClaim(claimName);
		}
		return null;
	}

	@GET
	@Path("configs")
	@RolesAllowed(Role.UNAUTHENTICATED)
	@Produces(MediaType.APPLICATION_JSON)
	public ArrayList<OidcConfig> getOidcConfs() {
		return oidcProviders.getOidcConfigs();
	}

	@POST
	@Path("flow")
	@RolesAllowed({ Role.UNAUTHENTICATED, Role.WEB_SERVER })
	@Produces(MediaType.APPLICATION_JSON)
	public URI startAuthentication(@QueryParam("id") String oidcName)
			throws URISyntaxException, com.nimbusds.oauth2.sdk.ParseException, IOException {

		OidcConfig oidcConfig = getOidcConfig(oidcName);

		// TODO get only once
		String authorizationEndpoint = OidcProvidersImpl.getMetadata(oidcConfig).getAuthorizationEndpointURI()
				.toString();

		ClientID clientID = new ClientID(oidcConfig.getClientId());

		// The client callback URL
		URI callback = new URI(oidcConfig.getRedirectPath());

		// Generate random state string to securely pair the callback to this request
		State state = new State();

		// Generate nonce for the ID token
		Nonce nonce = new Nonce();

		// Compose the OpenID authentication request (for the code flow)
		Builder request = new AuthenticationRequest.Builder(
				new ResponseType(oidcConfig.getResponseType()),
				new Scope(oidcConfig.getScope()),
				clientID,
				callback)
				.endpointURI(new URI(authorizationEndpoint))
				.state(state)
				.nonce(nonce);

		if (oidcConfig.getParameter() != null) {
			// this is how it was done in app, but possibility to add multiple parameters is
			// not documented in chipster-defaults.yaml
			for (String entry : Arrays.asList(oidcConfig.getParameter().split(" "))) {
				String[] parts = entry.split("=");
				if (parts.length != 2) {
					throw new InternalServerErrorException("cannot parse " + oidcConfig.getParameter());
				}
				String key = parts[0];
				String value = parts[1];
				logger.info("add customer parameter " + key + "=" + value);
				request.customParameter(key, value);
			}
		}

		// The URI to send the user-user browser to the OpenID provider
		return request.build().toURI();
	}

	private OidcConfig getOidcConfig(String oidcName) {
		List<OidcConfig> configs = oidcProviders.getOidcConfigs().stream()
				.filter(oc -> oc.getOidcName().equals(oidcName)).collect(Collectors.toList());

		if (configs.size() == 0) {
			throw new BadRequestException("oidc configuration not found");
		}

		if (configs.size() > 1) {
			throw new InternalServerErrorException("multiple oidc configurations found");
		}

		return configs.getFirst();
	}
}
