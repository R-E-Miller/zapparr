# Development signing key

`zapparr-dev.p12` signs every debug and release build, on any machine and in CI. Because the key
never changes, a newer APK always installs over an older one with `adb install -r`. That matters
for a launcher: uninstalling the app that is Home, with the stock launcher disabled, leaves the box
without a Home screen.

**This key is public.** Its password is in `app/build.gradle.kts`, and anyone can sign an APK with
it. It is fine for your own box. Before publishing builds for other people, create a private key,
keep it out of the repo, and sign releases with it. Switching keys means uninstalling once, so run
`python tools/debloat.py restore` first.

- Alias `zapparr-dev`, PKCS12, RSA 3072, valid until 2126.
- SHA-256: `B0:41:36:CC:24:C9:B6:51:7F:96:15:0B:FC:D1:80:AB:E3:B4:6E:BB:B7:2B:AC:26:25:0F:BB:3B:A6:BD:96:56`
