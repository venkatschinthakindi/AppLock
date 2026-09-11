# Release signing template

Never commit a keystore or password.

For local release signing, create a private `keystore.properties` file outside version control and wire it into `app/build.gradle.kts`.

For CI, use secrets equivalent to:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `CI_VERSION_CODE`
- `CI_VERSION_NAME`

Google Play App Signing should be enabled for the production application. Preserve the upload key securely and document recovery ownership.
