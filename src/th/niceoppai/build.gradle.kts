import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Niceoppai"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "th"
        baseUrl = "https://www.niceoppai.net"
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
}
