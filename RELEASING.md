# Releasing The Display Solution TV app

How to build a new version of the TV player and publish it to all three places it is sold or offered. The steps are
written so that any developer who is given access (see **Access** at the end) can do a release without help.

**The same steps with screenshots, a red box on everything to click: [`docs/release-guide/index.html`](docs/release-guide/index.html).**
Open it in a browser.

The app is `com.thedisplaysolution.player`. It is a WebView of `https://sign.thedisplaysolution.com/player`, so most
changes to what a TV shows are made on the website and need no new app version at all. A new version is needed only for
the Android side: the watchdog, the Back-hold menu, the updater, permissions, icons and the target SDK.

## The two builds (product flavours)

| Flavour | Goes to | Updates itself? | Devices | File |
|---|---|---|---|---|
| `site` | the website's **Developer → TV App**, and the **Amazon Appstore** | Yes, from the website (`AppUpdater`). A copy the Amazon Appstore installed asks nothing: Amazon updates it. | Fire TV, Android TV, Google TV, any Android box | signed **APK** |
| `play` | **Google Play** | No. Google Play updates it. Play allows no app to update itself, so this build has no `REQUEST_INSTALL_PACKAGES` and no install receiver. | Android TV and Google TV alone (`leanback` required) | signed **AAB** (App Bundle) |

Both are the same app with the same package and the same version. The difference lives in:

- `app/build.gradle.kts`: `productFlavors` and `BuildConfig.SELF_UPDATE`.
- `app/src/site/AndroidManifest.xml`: the updater's permission and receiver.
- `app/src/play/AndroidManifest.xml`: leanback is required.

## Before you start

1. **The release key.** `D:\Keys\the-display-solution-release.jks` on the owner's computer. Its passwords are the
   owner's: ask the owner, never put them in git, a chat or an email.
   - A copy of the key must be kept safe. Without it, no update can reach a TV that installed the app from the website.
   - Google Play is safer: it holds its own app-signing key (Play App Signing), and this key is only the **upload key**
     there. If it is ever lost, the account owner can ask Google for an upload key reset (Play Console → Test and
     release → App integrity → Play app signing).
2. **keystore.properties.** Copy `keystore.properties.example` to `keystore.properties` in the project root and fill it
   in. It is git-ignored and must stay that way.
   - Without it, build the files with Android Studio's **Build → Generate Signed App Bundle or APK** wizard instead.
     Choose the flavour (`siteRelease` or `playRelease`) in its last step.
3. **Java.** Use Android Studio's own JDK:
   `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

## 1. Raise the version

In `app/build.gradle.kts`:

- `versionCode` goes up by exactly **1**, every release. Every store refuses a code it has already seen, and Android
  installs nothing older over something newer.
- `versionName` is what people read, for example `"1.1"`.

Commit that change on its own: `Version 1.1 (versionCode 2): <what changed>`.

## 2. Build and check

```bash
./gradlew lintSiteDebug lintPlayDebug assembleSiteRelease bundlePlayRelease
```

Lint must report **0 errors**. These warnings are expected:

- `OldTargetApi`: targetSdk 36 on purpose. Read the comment in `build.gradle.kts` before changing it.
- `GradleDependency` and `NewerVersionAvailable`.

The files:

- `app/build/outputs/apk/site/release/app-site-release.apk`, for the website and Amazon.
- `app/build/outputs/bundle/playRelease/app-play-release.aab`, for Google Play.

Android Studio's signing wizard writes the same two files inside the `app` folder instead; the **locate** link in its
message at the bottom right opens that folder.

Check the APK's signature and version:

```bash
"%LOCALAPPDATA%/Android/Sdk/build-tools/<newest>/apksigner" verify --print-certs app/build/outputs/apk/site/release/app-site-release.apk
```

The signer must be `CN=The Display Solution`, with certificate SHA-256 `27911acd…28ee`.

Try both builds on the Android TV emulator (`TV_1080p`) before uploading. Install with
`adb install -r app-…-debug.apk`, then hold Back for 5 s (`adb shell input keyevent --duration 6000 KEYCODE_BACK`) to
open the menu:

- `site`: Check for updates asks the website.
- `play`: Check for updates says "This TV got the app from Google Play, which keeps it up to date."

## 3. Publish

### a. The website (Developer → TV App)

This is what every TV with the website's copy is offered.

1. Sign in to https://sign.thedisplaysolution.com as the super admin. Only the Super-Admin role can open
   **Developer → TV App**.
2. Drop `app-site-release.apk` on the upload box. The page reads the package, the version code and the name from the
   file.
3. Write the notes in a sentence or two, then press **Publish Version**.
4. Every TV with the app offers "Update available" when it next comes to the front. Android asks for one press on the
   TV to install it.

### b. Amazon Appstore

Fire TV is sold here.

1. https://developer.amazon.com/apps-and-games/console → **The Display Solution** → **Add upcoming version**.
2. **App files**: upload the same `app-site-release.apk`. Leave the supported devices as they are: Fire TV only, no
   tablets, no Automotive.
3. Change only what changed in the listing: **What's new**, and screenshots if the look changed.
4. Paste the reviewer's testing instructions again, just before **Submit**. The console forgets them when you leave that
   step. The text and the test account are in the owner's notes.
5. Submit. Amazon reviews it, usually within a few days, and updates every Appstore copy itself.

### c. Google Play

Android TV and Google TV are sold here.

1. https://play.google.com/console → **The Display Solution** → **Test and release**.
2. Pick a track:
   - **Closed testing** while the account is still in its first 14-day test. A personal account needs 12 testers opted
     in for 14 days in a row before Production unlocks.
   - **Production** after that.
3. **Create new release**, upload `app-play-release.aab`, and write the release notes.
4. **Review release → Start rollout**.
   - Android TV releases are also checked against Google's TV quality guidelines. A first TV release can take longer.
5. Google Play updates every Play copy itself.

## 4. After the release

- Tag the commit: `git tag v1.1 && git push --tags`. The owner pushes, or you push if you are given that right.
- Keep the signed files outside git, for example on the owner's backup drive. They are git-ignored (`*.apk`, `*.aab`).

## Access for a new developer

Give each person their own login, never a shared password. Remove the access when they leave.

| Place | How to give access | What to give |
|---|---|---|
| **GitHub** (`thedisplaysolution-android`) | Repository → Settings → Collaborators → Add people | Write |
| **Google Play Console** | Users and permissions → Invite new users → their email | For "The Display Solution": *Release to production, exclude devices, and use Play App Signing*, *Release apps to testing tracks*, *Manage testing tracks and edit tester lists*, *Edit store listing, pricing and distribution* and *View app information*. Avoid *Admin (all permissions)*, which lets them invite or remove people. |
| **Amazon Developer Console** | Settings → User Permissions → Add New → their email | Role **Developer**: upload and submit. **Administrator** also manages users and payments, so keep that one for the owner. |
| **The website** | Users → Invite to the platform | Developer → TV App needs the **Super-Admin** role, which only the primary super admin can give. Uploading a TV app version stays with the owner unless they decide otherwise. |
| **The release key** | Hand it over in person or through a password manager, never by email or chat | Only to someone the owner trusts to sign releases. |

Sources: [Play Console users and permissions](https://support.google.com/googleplay/android-developer/answer/9844686),
[Amazon account and permissions](https://developer.amazon.com/docs/app-submission/manage-account-and-permissions.html),
[Play app signing](https://developer.android.com/studio/publish/app-signing),
[Play's test requirement for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465),
[REQUEST_INSTALL_PACKAGES policy](https://support.google.com/googleplay/android-developer/answer/12085295).
