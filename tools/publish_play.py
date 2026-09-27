"""Upload one Android App Bundle to the existing internal and closed test tracks."""

import os
from pathlib import Path

import google.auth
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload


PACKAGE = "de.robinrehbein.mininetworks"
SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]


def main() -> None:
    bundle = Path(os.environ["PLAY_BUNDLE"])
    if not bundle.is_file():
        raise FileNotFoundError(bundle)
    version_code = int(os.environ["PLAY_VERSION_CODE"])
    version_name = os.environ["PLAY_VERSION_NAME"]
    closed_track = os.environ["PLAY_CLOSED_TRACK"]
    if closed_track in ("internal", "production"):
        raise ValueError("PLAY_CLOSED_TRACK must name an existing closed test track")

    credentials, _ = google.auth.default(scopes=SCOPES)
    service = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False)
    edits = service.edits()
    edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]

    tracks = {
        item["track"] for item in edits.tracks()
        .list(packageName=PACKAGE, editId=edit_id).execute().get("tracks", [])
    }
    required = {"internal", closed_track}
    if not required.issubset(tracks):
        raise RuntimeError(f"Missing Play test track(s): {sorted(required - tracks)}")

    uploaded = edits.bundles().upload(
        packageName=PACKAGE,
        editId=edit_id,
        media_body=MediaFileUpload(str(bundle), mimetype="application/octet-stream", resumable=True),
    ).execute()
    if int(uploaded["versionCode"]) != version_code:
        raise RuntimeError("Uploaded bundle has an unexpected version code")

    notes = [
        {"language": "de-DE", "text": "Aktuelle Verbesserungen und Fehlerbehebungen."},
        {"language": "en-US", "text": "Latest improvements and bug fixes."},
    ]
    for track in sorted(required):
        edits.tracks().update(
            packageName=PACKAGE,
            editId=edit_id,
            track=track,
            body={
                "track": track,
                "releases": [{
                    "name": version_name,
                    "versionCodes": [str(version_code)],
                    "status": "completed",
                    "releaseNotes": notes,
                }],
            },
        ).execute()

    edits.commit(packageName=PACKAGE, editId=edit_id, changesNotSentForReview=False).execute()
    print(f"Published version {version_name} ({version_code}) to {', '.join(sorted(required))}")


if __name__ == "__main__":
    main()
