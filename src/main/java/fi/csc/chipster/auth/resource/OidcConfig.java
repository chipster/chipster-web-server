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
	private String claimPrimaryUserId;
	private String claimSecondaryUserId;
	private String secondaryAuth;
	private String parameter;
	private String logoWidth;

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

	public String getClaimPrimaryUserId() {
		return claimPrimaryUserId;
	}

	public void setClaimPrimaryUserId(String claimPrimaryUserId) {
		this.claimPrimaryUserId = claimPrimaryUserId;
	}

	public String getClaimSecondaryUserId() {
		return claimSecondaryUserId;
	}

	public void setClaimSecondaryUserId(String claimSecondaryUserId) {
		this.claimSecondaryUserId = claimSecondaryUserId;
	}

	public String getSecondaryAuth() {
		return secondaryAuth;
	}

	public void setSecondaryAuth(String secondaryAuth) {
		this.secondaryAuth = secondaryAuth;
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
}
