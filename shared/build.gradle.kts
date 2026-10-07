plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.szymi.mapy.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
