plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.lokalno.localfoldersyncclient"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.localfoldersyncclient"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        // Option A: The most common Kotlin DSL syntax
        viewBinding = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.grpc.okhttp)       // transport for Android
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf)
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.32.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.75.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                // refer to the plugin you created above
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}