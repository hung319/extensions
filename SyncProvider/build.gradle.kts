@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties

dependencies {
    implementation("com.google.android.material:material:1.4.0")
}

// use an integer for version numbers
version = 4


android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}


cloudstream {
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    authors = listOf("anhdaden")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Others",
    )

    iconUrl = "https://clouding.vn/wp-content/uploads/2022/10/cropped-Cloud-Services-192x192.png"
    description = "Đồng bộ cài đặt và lịch sử xem giữa các thiết bị"
    requiresResources = true
}
