plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.mo.glassmic.xposed"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-Wall"
                )
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.1.12297006"

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":glamic-core"))
    compileOnly(libs.libxposed.api)
    compileOnly(libs.legacy.xposed.api)
    implementation(libs.shadowhook)
}
