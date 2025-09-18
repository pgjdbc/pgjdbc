import java.time.Duration

plugins {
    id("build-logic.build-params")
    id("com.gradleup.nmcp.aggregation")
}

nmcpAggregation {
    centralPortal {
        username.set(providers.gradleProperty("centralPortalUsername")
          .orElse(providers.environmentVariable("CENTRAL_PORTAL_USERNAME")))
        password.set(providers.gradleProperty("centralPortalPassword")
          .orElse(providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")))
        publishingType.set(buildParameters.centralPortal.publishingType.name)
        // WA for https://github.com/GradleUp/nmcp/issues/52
        publicationName.set(provider { "${project.name}-${project.version}.zip" })
        verificationTimeout.set(Duration.ofMinutes(buildParameters.centralPortal.verificationTimeout.toLong()))
    }
}
