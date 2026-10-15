# Vedic astrology 69

Android app built by App Builder.

- Package: com.builder.vedicastrology69
- Start page: https://projects-and-apps.vercel.app/vedic-astro-suite/vedic-astro-suite-readable.html
- GitHub Actions builds the APK on every push to main. Find it under Releases → latest.

## What your web page can call

The page can use the phone through the `Native` object:

- Native.toast(message)
- Native.notifyUser(title, body)
- Native.requestNotifications()
- Native.shareText(text, title)
- Native.vibrate(milliseconds)
- Native.keepAwake(true) or Native.keepAwake(false)
- Native.isOnline()
- Native.reload()
- Native.appInfo()

Inside the app, `window.isNativeApp` is true.
Downloads are saved to the Downloads folder. Links to other websites open in the browser.
