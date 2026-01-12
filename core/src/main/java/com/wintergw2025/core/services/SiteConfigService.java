package com.wintergw2025.core.services;

/**
 * Service to provide site-wide configuration settings.
 */
public interface SiteConfigService {

    /**
     * Gets the configured publish host URL.
     * @return the publish host URL
     */
    String getPublishHost();
}
