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
        validationTimeout = Duration.ofMinutes(buildParameters.centralPortal.validationTimeout.toLong())
    }
}
