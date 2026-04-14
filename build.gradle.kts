plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace  = "com.tonnom.localcsv"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId    = "com.github.TON_USERNAME_GITHUB"
                artifactId = "localcsv-android"
                version    = "1.0.0"
            }
        }
    }
}
