description = "Classes that make testing pgjdbc easier"

dependencies {
    api("org.checkerframework:checker-qual")

    implementation(project(":postgresql"))
    implementation("junit:junit")
}
