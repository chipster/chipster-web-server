package fi.csc.chipster.auth.resource;

public class OidcConfig {

	private String issuer;
	private String clientId;
	private String redirectUri;
	private String responseType;
	private String logo;
	private Integer priority;
	private Boolean verifiedEmailOnly;
	private String oidcName;
	private String claimOrganization;
	private String claimPreviousUserId;

	public OidcConfig(String issuer, String clientId, String redirectUri, String responseType,
			String logo, Integer priority, Boolean verifiedEmailOnly, String oidcName, String claimOrganization, String claimPreviousUserId) {
		
		this.setIssuer(issuer);
		this.setClientId(clientId);
		this.setRedirectUri(redirectUri);
		this.setResponseType(responseType);
		this.setLogo(logo);
		this.setPriority(priority);
		this.setVerifiedEmailOnly(verifiedEmailOnly);
		this.setOidcName(oidcName);
		this.setClaimOrganization(claimOrganization);
		this.setClaimPreviousUserId(claimPreviousUserId);
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

	public String getRedirectUri() {
		return redirectUri;
	}

	public void setRedirectUri(String redirectUri) {
		this.redirectUri = redirectUri;
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

	public String getClaimPreviousUserId() {
		return claimPreviousUserId;
	}

	public void setClaimPreviousUserId(String claimPreviousUserId) {
		this.claimPreviousUserId = claimPreviousUserId;
	}

}
