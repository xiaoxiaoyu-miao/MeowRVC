import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "io.github.neboyang.voicechanger"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.annotation)
    api(libs.onnxruntime.android)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.neboyang"
            artifactId = "voicechanger"
            version = "2.1.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
