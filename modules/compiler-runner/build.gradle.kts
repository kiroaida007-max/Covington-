dependencies {
    api(project(":contracts"))
}

tasks.withType<Test>().configureEach {
    systemProperty("apkproject.processProbe.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
