plugins { `kotlin-dsl` }

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(gradleApi())
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation(localGroovy())
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }
