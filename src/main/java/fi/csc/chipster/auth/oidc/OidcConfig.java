package fi.csc.chipster.auth.oidc;

/**
 * Configuration for one login method
 * 
 * All the fields are visible in the server. The same class is also used to give
 * public information about login method to the app, but then all confidential
 * or unnecessary fields are set to null.
 */
public class OidcConfig {

	private String issuer;
	private String clientId;
	private String redirectPath;
	private String responseType;
	private String logo;
	private Integer priority;
	private Boolean verifiedEmailOnly;
	private String oidcName;
	private String claimOrganization;
	private String text;
	private String claimUserId;
	private String parameter;
	private String userIdPrefix;
	private String requiredClaimKey;
	private String requiredClaimValue;
	private String requiredClaimValueComparison;
	private String requiredClaimError;
	private Boolean queryUserInfo;
	private String description;
	private String scope;
	private String clientSecret;
	private String jwsAlgorithm;
	private String ipLimit;

	public OidcConfig() {
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getRedirectPath() {
		return redirectPath;
	}

	public void setRedirectPath(String redirectUri) {
		this.redirectPath = redirectUri;
	}

	public String getResponseType() {
		return responseType;
	}

	public void setResponseType(String responseType) {
		this.responseType = responseType;
	}

	public String getLogo() {
		return logo;
	}

	public void setLogo(String logo) {
		this.logo = logo;
	}

	public Boolean getVerifiedEmailOnly() {
		return verifiedEmailOnly;
	}

	public void setVerifiedEmailOnly(Boolean verifiedEmailOnly) {
		this.verifiedEmailOnly = verifiedEmailOnly;
	}

	public Integer getPriority() {
		return priority;
	}

	public void setPriority(Integer priority) {
		this.priority = priority;
	}

	public String getOidcName() {
		return oidcName;
	}

	public void setOidcName(String oidcName) {
		this.oidcName = oidcName;
	}

	public String getClaimOrganization() {
		return claimOrganization;
	}

	public void setClaimOrganization(String claimOrganization) {
		this.claimOrganization = claimOrganization;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public String getClaimUserId() {
		return claimUserId;
	}

	public void setClaimUserId(String claimUserId) {
		this.claimUserId = claimUserId;
	}

	public String getParameter() {
		return parameter;
	}

	public void setParameter(String parameter) {
		this.parameter = parameter;
	}

	public String getUserIdPrefix() {
		return userIdPrefix;
	}

	public void setUserIdPrefix(String userIdPrefix) {
		this.userIdPrefix = userIdPrefix;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getDescription() {
		return this.description;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public String getRequiredClaimKey() {
		return requiredClaimKey;
	}

	public void setRequiredClaimKey(String requiredClaimKey) {
		this.requiredClaimKey = requiredClaimKey;
	}

	public String getRequiredClaimValue() {
		return requiredClaimValue;
	}

	public void setRequiredClaimValue(String requiredClaimValue) {
		this.requiredClaimValue = requiredClaimValue;
	}

	public String getRequiredClaimValueComparison() {
		return requiredClaimValueComparison;
	}

	public void setRequiredClaimValueComparison(String requiredClaimValueComparison) {
		this.requiredClaimValueComparison = requiredClaimValueComparison;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public String getClientSecret() {
		return this.clientSecret;
	}

	public String getJwsAlgorithm() {
		return jwsAlgorithm;
	}

	public void setJwsAlgorithm(String jwsAlgorithm) {
		this.jwsAlgorithm = jwsAlgorithm;
	}

	public Boolean getQueryUserInfo() {
		return queryUserInfo;
	}

	public void setQueryUserInfo(Boolean queryUserInfo) {
		this.queryUserInfo = queryUserInfo;
	}

	public String getRequiredClaimError() {
		return requiredClaimError;
	}

	public void setRequiredClaimError(String requiredClaimError) {
		this.requiredClaimError = requiredClaimError;
	}

	public String getIpLimit() {
		return ipLimit;
	}

	public void setIpLimit(String ipLimit) {
		this.ipLimit = ipLimit;
	}
}
