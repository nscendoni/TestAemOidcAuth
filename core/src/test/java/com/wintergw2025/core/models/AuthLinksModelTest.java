package com.wintergw2025.core.models;

import com.wintergw2025.core.services.SiteConfigService;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class AuthLinksModelTest {

    @Mock
    private SiteConfigService siteConfigService;

    @Test
    void testModelWithSiteConfigService(AemContext context) {
        // Register the mock service
        context.registerService(SiteConfigService.class, siteConfigService);
        when(siteConfigService.getPublishHost()).thenReturn("http://example.com:8085");

        // Register Sling Models to enable adaptation
        context.addModelsForClasses(AuthLinksModel.class);
        
        // Create a resource and adapt to model
        context.build().resource("/content/test").commit();
        context.currentResource("/content/test");

        AuthLinksModel model = context.request().getResource().adaptTo(AuthLinksModel.class);

        assertNotNull(model);
        assertEquals("http://example.com:8085", model.getPublishHost());
        assertEquals("http://example.com:8085/content/wintergw2025/us/en/oauth2-authenticated.html", 
                model.getOauth2PageUrl());
        assertEquals("http://example.com:8085/content/wintergw2025/us/en/oauth2-authenticated.html?redirect=/content/wintergw2025/us/en.html", 
                model.getOauth2PageWithRedirectUrl());
        assertEquals("http://example.com:8085/content/wintergw2025/us/en/saml-authenticated.html", 
                model.getSamlPageUrl());
    }

    @Test
    void testModelWithDefaultPublishHost(AemContext context) {
        // Register a service that returns the default value
        context.registerService(SiteConfigService.class, siteConfigService);
        when(siteConfigService.getPublishHost()).thenReturn("http://localhost:8085");
        
        // Register Sling Models to enable adaptation
        context.addModelsForClasses(AuthLinksModel.class);
        context.build().resource("/content/test").commit();
        context.currentResource("/content/test");

        AuthLinksModel model = context.request().getResource().adaptTo(AuthLinksModel.class);

        assertNotNull(model);
        assertEquals("http://localhost:8085", model.getPublishHost());
        assertEquals("http://localhost:8085/content/wintergw2025/us/en/oauth2-authenticated.html", 
                model.getOauth2PageUrl());
    }

    @Test
    void testGetOauth2PageUrl(AemContext context) {
        context.registerService(SiteConfigService.class, siteConfigService);
        when(siteConfigService.getPublishHost()).thenReturn("https://publish.example.com");

        context.addModelsForClasses(AuthLinksModel.class);
        context.build().resource("/content/test").commit();
        context.currentResource("/content/test");

        AuthLinksModel model = context.request().getResource().adaptTo(AuthLinksModel.class);

        assertEquals("https://publish.example.com/content/wintergw2025/us/en/oauth2-authenticated.html", 
                model.getOauth2PageUrl());
    }

    @Test
    void testGetOauth2PageWithRedirectUrl(AemContext context) {
        context.registerService(SiteConfigService.class, siteConfigService);
        when(siteConfigService.getPublishHost()).thenReturn("https://publish.example.com");

        context.addModelsForClasses(AuthLinksModel.class);
        context.build().resource("/content/test").commit();
        context.currentResource("/content/test");

        AuthLinksModel model = context.request().getResource().adaptTo(AuthLinksModel.class);

        assertEquals("https://publish.example.com/content/wintergw2025/us/en/oauth2-authenticated.html?redirect=/content/wintergw2025/us/en.html", 
                model.getOauth2PageWithRedirectUrl());
    }

    @Test
    void testGetSamlPageUrl(AemContext context) {
        context.registerService(SiteConfigService.class, siteConfigService);
        when(siteConfigService.getPublishHost()).thenReturn("https://publish.example.com");

        context.addModelsForClasses(AuthLinksModel.class);
        context.build().resource("/content/test").commit();
        context.currentResource("/content/test");

        AuthLinksModel model = context.request().getResource().adaptTo(AuthLinksModel.class);

        assertEquals("https://publish.example.com/content/wintergw2025/us/en/saml-authenticated.html", 
                model.getSamlPageUrl());
    }
}
