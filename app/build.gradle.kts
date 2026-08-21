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
		versionCode = 1
		versionName = "0.1.0"
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

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		// Needed by the ported MIDI stack's Log.kt, which checks BuildConfig.DEBUG
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

	// Required by the ported MIDI stack (MidiConnection.kt uses CoroutineScope/channels for USB I/O)
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
