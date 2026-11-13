import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Debug"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://tls.peet.ws/api/all"
    }

    source {
        name = "Debug (Cronet)"
        lang = "all"
        baseUrl = "https://tls.peet.ws/api/all"
    }
}

dependencies {
    implementation(project(":lib:cronet"))
    implementation("com.google.zxing:core:3.5.3")
}
