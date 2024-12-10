tasks.withType<AbstractArchiveTask>().configureEach {
    // Ensure builds are reproducible
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions {
        user {
            read = true
            write = true
            execute = true
        }
        group {
            read = true
            write = true
            execute = true
        }
        other {
            read = true
            execute = true
        }
    }
    filePermissions {
        user {
            read = true
            write = true
        }
        group {
            read = true
            write = true
        }
        other {
            read = true
        }
    }
}
