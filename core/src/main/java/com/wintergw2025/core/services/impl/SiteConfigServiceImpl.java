package com.wintergw2025.core.services.impl;

import com.wintergw2025.core.config.SiteConfig;
import com.wintergw2025.core.services.SiteConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;

@Component(service = SiteConfigService.class, immediate = true)
@Designate(ocd = SiteConfig.class)
public class SiteConfigServiceImpl implements SiteConfigService {

    private String publishHost;

    @Activate
    @Modified
    protected void activate(SiteConfig config) {
        this.publishHost = config.publishHost();
    }

    @Override
    public String getPublishHost() {
        return publishHost;
    }
}
