import json
import os
import sys
from pathlib import Path

TRUSTED_ASSOCIATIONS = {"OWNER", "MEMBER"}


def is_authorized(author: str, association: str, config_path: Path) -> tuple[bool, str]:
    association = association.upper().strip()
    if association in TRUSTED_ASSOCIATIONS:
        return True, f"Authorized by GitHub association ({association})"

    with config_path.open(encoding="utf-8") as config_file:
        data = json.load(config_file)

    authorized_contributors = set(data.get("contributors", []))
    if author in authorized_contributors:
        return True, "Authorized by .github/authorized_contributors.json"

    return False, "Author is neither trusted by GitHub association nor in authorized list"


def write_output(name: str, value: str) -> None:
    output_file = os.getenv("GITHUB_OUTPUT")
    if output_file:
        with open(output_file, "a", encoding="utf-8") as out_file:
            out_file.write(f"{name}={value}\n")


def main() -> int:
    if len(sys.argv) != 4:
        print(
            "Usage: check-pr-authorized.py <author_login> <author_association> <authorized_contributors_json>",
            file=sys.stderr,
        )
        return 2

    _, author, association, config_path_str = sys.argv
    config_path = Path(config_path_str)

    authorized, reason = is_authorized(author, association, config_path)
    print(reason)

    write_output("authorized", "true" if authorized else "false")
    write_output("reason", reason)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
