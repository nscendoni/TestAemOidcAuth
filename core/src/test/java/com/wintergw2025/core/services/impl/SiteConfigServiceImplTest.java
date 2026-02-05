package com.wintergw2025.core.services.impl;

import com.wintergw2025.core.config.SiteConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteConfigServiceImplTest {

    @Mock
    private SiteConfig siteConfig;

    private SiteConfigServiceImpl fixture;

    @BeforeEach
    void setUp() {
        fixture = new SiteConfigServiceImpl();
    }

    @Test
    void testGetPublishHost() {
        when(siteConfig.publishHost()).thenReturn("http://localhost:8085");
        
        fixture.activate(siteConfig);

        assertEquals("http://localhost:8085", fixture.getPublishHost());
    }

    @Test
    void testActivateWithCustomHost() {
        when(siteConfig.publishHost()).thenReturn("https://publish.example.com");
        
        fixture.activate(siteConfig);

        assertEquals("https://publish.example.com", fixture.getPublishHost());
    }

    @Test
    void testModifiedConfiguration() {
        // Initial activation
        when(siteConfig.publishHost()).thenReturn("http://localhost:8085");
        fixture.activate(siteConfig);
        assertEquals("http://localhost:8085", fixture.getPublishHost());

        // Modified configuration
        when(siteConfig.publishHost()).thenReturn("https://modified.example.com");
        fixture.activate(siteConfig);
        assertEquals("https://modified.example.com", fixture.getPublishHost());
    }

    @Test
    void testGetPublishHostWithProductionUrl() {
        when(siteConfig.publishHost()).thenReturn("https://www.production.com");
        
        fixture.activate(siteConfig);

        assertEquals("https://www.production.com", fixture.getPublishHost());
    }
}
