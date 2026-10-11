dependencies {
    api(project(":contracts"))
}

tasks.withType<Test>().configureEach {
    systemProperty("apkproject.archiveProbe.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
