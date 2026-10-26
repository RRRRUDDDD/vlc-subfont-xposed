plugins {
    id("com.android.application")
}

android {
    namespace = "com.vlcsubfont.module"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.vlcsubfont.module"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    signingConfigs {
        create("release") {
            // 复用 debug 签名，方便直接安装 release 包
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // .so 保持无压缩存入 APK：LSPosed 注入的 nativeLibraryDirectories 是
    // base.apk!/lib/<abi> 形式，仅无压缩条目可被 linker 直接映射
    // （useLegacyPackaging 必须保持默认 false，勿开启）

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
