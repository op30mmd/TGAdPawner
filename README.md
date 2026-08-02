# TGAdPawner

TGAdPawner is a lightweight and efficient Xposed module designed to remove sponsored messages and advertisements from Telegram and its popular forks (Plus Messenger, Nekogram, etc.). It leverages the libXposed API to provide a seamless, ad-free experience without modifying the original APKs.

## Features

- **Multi-Layered Ad Blocking**:
    - **Global Kill-Switch**: Forces `isSponsoredDisabled` to `true` in Telegram's controller.
    - **Message Filtering**: Intercepts `MessageObject.isSponsored` and returns `false`.
    - **Data Suppression**: Returns empty results for `getSponsoredMessages` to prevent ad data from ever loading.
    - **UI Cleanup**: Automatically collapses and hides `BotAdView` components if they attempt to render.
    - **Defensive Hooks**: Suppresses visibility of sponsored cells in chat lists and activities.
- **Broad Compatibility**: Supports official Telegram, Plus Messenger, and Nekogram.
- **Inline image WebP conversion**: Hooks `SendMessagesHelper.sendInlineBotResult`, marks inline image results as `image/webp`, and converts an already-downloaded local image payload to WebP before sending.
- **Performance Focused**: Minimal overhead using efficient hook points.
- **Built with libXposed**: Modern Xposed API compatibility (requires a libXposed-compatible manager like LSPosed).

## Prerequisites

- An Android device with root access.
- A libXposed-compatible framework installed (e.g., [LSPosed](https://github.com/LSPosed/LSPosed)).
- A supported Telegram client:
    - Official Telegram (`org.telegram.messenger`)
    - Plus Messenger (`org.telegram.plus`)
    - Nekogram (`tw.nekomimi.nekogram`)
    - Momogram (`momo.gram`)

## Installation

1. Download the latest `app-release-signed.apk` from the [Releases](https://github.com/op30mmd/TGAdPawner/releases) page (or build it yourself).
2. Install the APK on your Android device.
3. Open your Xposed manager (e.g., LSPosed).
4. Locate **TGAdPawner** in the modules list and enable it.
5. Ensure that the target Telegram application is selected in the module's scope.
6. Force stop and restart Telegram.

## Building from Source

To build the module yourself, you will need Android Studio or the Android SDK.

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/TGAdPawner.git
   ```
2. Open the project in Android Studio or use the command line:
   ```bash
   ./gradlew assembleDebug
   ```
3. The generated APK will be located in `app/build/outputs/apk/debug/app-debug.apk`.

## How it Works

TGAdPawner hooks into several key points of the Telegram application:
- **`MessagesController`**: To disable the global advertisement flag.
- **`MessageObject`**: To mark specific messages as non-sponsored.
- **`ChatActivity`**: To prevent the insertion of sponsored message counts into the message list.
- **`BotAdView`**: To hide ad containers in bot interactions.

## Disclaimer

This project is for educational purposes only. Use it at your own risk. The developers are not responsible for any issues arising from the use of this module, including potential account bans or violations of Telegram's Terms of Service. This module does not collect any user data.

## License

This project is licensed under the [Apache License 2.0](LICENSE).
