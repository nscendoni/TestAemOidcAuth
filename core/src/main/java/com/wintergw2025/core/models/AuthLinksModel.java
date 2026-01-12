package com.wintergw2025.core.models;

import com.wintergw2025.core.services.SiteConfigService;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;

import javax.annotation.PostConstruct;

@Model(adaptables = Resource.class)
public class AuthLinksModel {

    @OSGiService
    private SiteConfigService siteConfigService;

    private String publishHost;

    @PostConstruct
    protected void init() {
        publishHost = siteConfigService != null ? siteConfigService.getPublishHost() : "http://localhost:8085";
    }

    public String getPublishHost() {
        return publishHost;
    }

    public String getOauth2PageUrl() {
        return publishHost + "/content/wintergw2025/us/en/oauth2-authenticated.html";
    }

    public String getOauth2PageWithRedirectUrl() {
        return publishHost + "/content/wintergw2025/us/en/oauth2-authenticated.html?redirect=/content/wintergw2025/us/en.html";
    }

    public String getSamlPageUrl() {
        return publishHost + "/content/wintergw2025/us/en/saml-authenticated.html";
    }
}
