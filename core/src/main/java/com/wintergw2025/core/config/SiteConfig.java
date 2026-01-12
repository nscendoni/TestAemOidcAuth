package com.wintergw2025.core.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Site Configuration", description = "Configuration for site-wide settings")
public @interface SiteConfig {

    @AttributeDefinition(name = "Publish Host", description = "The publish host URL (e.g., https://publish.example.com)")
    String publishHost() default "http://localhost:8085";
}
