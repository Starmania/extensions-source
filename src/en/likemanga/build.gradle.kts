import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LikeManga"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"
    theme = "madara"

    source {
        lang = "en"
        baseUrl = "https://www.likemanga.co.uk"
        versionId = 2
    }

    deeplink {
        host("www.likemanga.co.uk")
        path("/manga/..*")
    }
}
