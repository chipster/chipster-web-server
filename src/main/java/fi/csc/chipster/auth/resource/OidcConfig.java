package fi.csc.chipster.auth.resource;

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
	private String logoWidth;
	private String userIdPrefix;
	private String appId;
	private String requireClaim;
	private String requireUserinfoClaim;
	private String description;
	private String scope;

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

	public String getLogoWidth() {
		return logoWidth;
	}

	public void setLogoWidth(String logoWidth) {
		this.logoWidth = logoWidth;
	}

	public String getUserIdPrefix() {
		return userIdPrefix;
	}

	public void setUserIdPrefix(String userIdPrefix) {
		this.userIdPrefix = userIdPrefix;
	}

	public String getAppId() {
		return appId;
	}

	public void setAppId(String appId) {
		this.appId = appId;
	}

	public String getRequireClaim() {
		return requireClaim;
	}

	public void setRequireClaim(String requireClaim) {
		this.requireClaim = requireClaim;
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

	public String getRequireUserinfoClaim() {
		return requireUserinfoClaim;
	}

	public void setRequireUserinfoClaim(String requireUserinfoClaim) {
		this.requireUserinfoClaim = requireUserinfoClaim;
	}
}
