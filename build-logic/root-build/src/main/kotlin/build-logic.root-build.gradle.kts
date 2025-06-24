import java.time.Duration

plugins {
    id("build-logic.build-params")
    id("com.gradleup.nmcp.aggregation")
}

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
        password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
        publishingType = buildParameters.centralPortal.publishingType.name
        // WA for https://github.com/GradleUp/nmcp/issues/52
        publicationName = provider { "${project.name}-${project.version}.zip" }
        verificationTimeout = Duration.ofMinutes(buildParameters.centralPortal.verificationTimeout.toLong())
    }
}
