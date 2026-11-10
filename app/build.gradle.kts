plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

android {
	namespace = "com.bobbypfreely.lpbf"
	compileSdk = 34

	defaultConfig {
		applicationId = "com.bobbypfreely.lpbf"
		minSdk = 26
		targetSdk = 34
		versionCode = 66
		versionName = "0.2.1-beta"
	}

	// Same keystore for debug + release so CI builds an installable APK for sideload.
	signingConfigs {
		create("appSign") {
			storeFile = file("lpbf-debug.keystore")
			storePassword = "lpbfdebug"
			keyAlias = "lpbf-debug"
			keyPassword = "lpbfdebug"
		}
	}

	buildTypes {
		debug {
			signingConfig = signingConfigs.getByName("appSign")
		}
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("appSign")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		buildConfig = true
	}
}

dependencies {
	implementation("androidx.core:core-ktx:1.13.1")
	implementation("androidx.appcompat:appcompat:1.7.0")
	implementation("com.google.android.material:material:1.12.0")
	implementation("androidx.viewpager2:viewpager2:1.1.0")
	implementation("androidx.fragment:fragment-ktx:1.8.2")
	implementation("androidx.activity:activity-ktx:1.9.1")
	implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

	implementation("androidx.media3:media3-exoplayer:1.4.1")
	implementation("androidx.media3:media3-common:1.4.1")

	implementation("androidx.documentfile:documentfile:1.0.1")
}
