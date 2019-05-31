package fi.csc.chipster.auth.resource;

public class OidcConfig {

	private String issuer;
	private String clientId;
	private String clientSecret;
	private String redirectUri;
	private String responseType;
	private String logo;
	private Integer priority;
	private Boolean verifiedEmailOnly;
	private String oidcName;

	public OidcConfig(String issuer, String clientId, String clientSecret, String redirectUri, String responseType,
			String logo, Integer priority, Boolean verifiedEmailOnly, String oidcName) {
		
		this.setIssuer(issuer);
		this.setClientId(clientId);
		this.setClientSecret(clientSecret);
		this.setRedirectUri(redirectUri);
		this.setResponseType(responseType);
		this.setLogo(logo);
		this.setPriority(priority);
		this.setVerifiedEmailOnly(verifiedEmailOnly);
		this.setOidcName(oidcName);
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

	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
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

}
