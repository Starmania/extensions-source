import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Tooncubus"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "zeistmanga"

    source {
        lang = "id"
        baseUrl = "https://www.tooncubus.top"
    }
}
