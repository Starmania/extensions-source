from pathlib import Path
import os
import shutil

PR_NUMBER = os.getenv("PR_NUMBER", "").strip()
REPO_APK_DIR = Path(os.getenv("REPO_APK_DIR", "repo/apk"))


def with_pr_suffix(name: str, pr_number: str) -> str:
    if not pr_number:
        return name

    if name.endswith(".apk"):
        return f"{name[:-4]}-{pr_number}.apk"

    return f"{name}-{pr_number}"

shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

for apk in Path.home().joinpath("apk-artifacts").glob("**/*.apk"):
    apk_name = apk.name.replace("-release.apk", ".apk")
    apk_name = with_pr_suffix(apk_name, PR_NUMBER)

    shutil.move(apk, REPO_APK_DIR.joinpath(apk_name))
