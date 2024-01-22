// tag::use-plugin[]
plugins {
// end::use-plugin[]
    id("java-library")
// tag::use-plugin[]
    id("ivy-publish")
}
// end::use-plugin[]

group = "org.gradle.sample"
version = "1.0"

// tag::publish-component[]
// tag::repositories[]
publishing {
    // end::repositories[]
    publications {
        create<IvyPublication>("ivyJava") {
            from(components["java"])
        }
    }
// end::publish-component[]
// tag::repositories[]
    repositories {
        ivy {
            // change to point to your repo, e.g. http://my.org/repo
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
// tag::publish-component[]
}
// end::publish-component[]
// end::repositories[]
