# Switch Unpacker for Android

Android version of the Switch Unpacker idea: unpack `.nsp` / `.xci` files into
`exefs` and `romfs` (plus optional control, legal, data) with one tap.
Written in Kotlin. It runs hactool 1.4.0, compiled for Android in the build.

Use it only with games you own and keys dumped from your own console.

## Get the APK

1. Create a new GitHub repository and upload everything in this folder
   (including the hidden `.github` folder).
2. Open the **Actions** tab, then **Build APK**. It starts by itself on upload.
3. When it finishes, open the run and download **SwitchUnpacker-apk**.
4. Unzip it and install `app-debug.apk` on your phone.

## Use

1. Choose the game file and your `prod.keys`.
2. Choose an output folder (default `/sdcard/SwitchUnpacker`).
3. Pick the folder naming and extras, then tap **Unpack**.
4. Allow "all files access" when asked.

## Notes

- Files picked from phone or SD card storage are used in place. Files from
  cloud apps are copied first and need extra free space.
- Needs a 64-bit phone (arm64).
- Same limits as the Windows version: base games only, no `.nsz`/`.xcz`,
  no Switch 2 games, keys must be new enough for the game.

## Credits

- hactool by SciresM (ISC license)
- Original Windows tool: kabouls/Switch-Unpacker
