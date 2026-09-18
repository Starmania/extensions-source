import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Drama"
    versionCode = 54
    contentWarning = ContentWarning.NSFW // or MIXED, please confirm
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://mangadrama.com"
    }
}
