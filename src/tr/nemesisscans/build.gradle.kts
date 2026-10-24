import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Nemesis scans"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "Nemesisscans"
        lang = "tr"
        baseUrl = "https://nemesisscans.com"
        // Left MangaThemesia for a custom API; old /manga/<slug>/ entries cannot be mapped to series ids
        versionId = 2
    }

    deeplink {
        path("/series/..*")
    }
}
