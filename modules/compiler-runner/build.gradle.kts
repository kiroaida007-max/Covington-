dependencies {
    api(project(":contracts"))
    implementation("net.java.dev.jna:jna:5.17.0")
}

tasks.withType<Test>().configureEach {
    systemProperty("apkproject.processProbe.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
