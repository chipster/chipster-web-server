package fi.csc.chipster.auth.oidc;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ParseException;
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
import com.nimbusds.openid.connect.sdk.claims.UserInfo;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;

/**
 * Helper functions for the Nimbus library
 * 
 * These are taken directly from the examples to make sure that the library is
 * used correctly. This should make it also easier to verify it afterwards, if
 * there is a need to be compared these to the examples. Only trivial
 * modifications were done to have all necessary information available in the
 * call site.
 */
public class NimbusHelpers {

	/*
	 * Follows example
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
	 * openid-connect/oidc-auth
	 */
	public static Builder createAuthentiationRequest(String clientIdString, String callbackString, State state,
			Nonce nonce, String responseType, String[] scopeArray, String authorizationEndpoint) {

		// The client ID provisioned by the OpenID provider when
		// the client was registered
		ClientID clientID = new ClientID(clientIdString);

		// The client callback URL
		URI callback;
		try {
			callback = new URI(callbackString);
		} catch (URISyntaxException e) {
			throw new InternalServerErrorException("failed to parse callback");
		}

		// Compose the OpenID authentication request (for the code flow)
		Builder request;
		try {
			request = new AuthenticationRequest.Builder(
					new ResponseType(responseType),
					new Scope(scopeArray),
					clientID,
					callback)
					.endpointURI(new URI(authorizationEndpoint))
					.state(state)
					.nonce(nonce);
		} catch (URISyntaxException e) {
			throw new InternalServerErrorException("failed to parse authorization endpoint");
		}

		return request;
	}

	/*
	 * Follows example
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
	 * openid-connect/token-request
	 */
	public static OIDCTokenResponse tokenRequest(OidcConfig oidcConfig, AuthorizationCode code,
			URI tokenEndpoint, String[] scopeArray, String callbackPath) {
		// Construct the code grant from the code obtained from the authz endpoint
		// and the original callback URI used at the authz endpoint
		URI callback;
		try {
			callback = new URI(callbackPath);
		} catch (URISyntaxException e) {
			throw new InternalServerErrorException("failed to parse callback path", e);
		}
		AuthorizationGrant codeGrant = new AuthorizationCodeGrant(code, callback);

		// The credentials to authenticate the client at the token endpoint
		ClientID clientID = new ClientID(oidcConfig.getClientId());
		Secret clientSecret = new Secret(oidcConfig.getClientSecret());
		ClientAuthentication clientAuth = new ClientSecretBasic(clientID, clientSecret);
		Scope scope = new Scope(scopeArray);

		// Make the token request.
		// The example calls TokenRequest without the scope argument, but that is
		// deprecated.
		TokenRequest request = new TokenRequest(tokenEndpoint, clientAuth, codeGrant, scope);

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
	 * Follows example
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/
	 * openid-connect/userinfo
	 */
	public static UserInfo userInfo(AccessToken accessToken, OIDCProviderMetadata metadata) {
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

	/**
	 * Follows examle in
	 * https://connect2id.com/products/nimbus-oauth-openid-connect-sdk/examples/openid-connect/oidc-auth
	 * 
	 * @param uri
	 * @param state
	 * @return
	 */
	public static AuthorizationCode parseResponse(URI requestUri, String stateInSession) {

		AuthenticationResponse response;
		try {
			response = AuthenticationResponseParser.parse(requestUri);
		} catch (ParseException e) {
			throw new InternalServerErrorException("failed to parse authentication response", e);
		}

		// Check the state
		if (!response.getState().toString().equals(stateInSession)) {
			throw new BadRequestException("state doesn't match");
		}

		if (response instanceof AuthenticationErrorResponse) {
			// The OpenID provider returned an error
			throw new ForbiddenException(
					"OIDC provider returned an error: " + response.toErrorResponse().getErrorObject().getCode() + " "
							+ response.toErrorResponse().getErrorObject().getDescription());
		}

		// Retrieve the authorisation code, to use it later at the token endpoint
		return response.toSuccessResponse().getAuthorizationCode();
	}
}
