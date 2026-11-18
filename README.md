# Spendr

A personal expense tracker for Android that builds itself from your bank SMS. Everything stays on the phone.

**[⬇️ Download the latest APK](https://github.com/Gopesh456/Spendr-1.0/releases/latest)** · Android 8.0+ · [MIT licensed](LICENSE) · [Contributions welcome](CONTRIBUTING.md)

## What it does

- **Tracks spending automatically** from Indian bank / UPI / card SMS (tuned on IDFC FIRST, SBI, HDFC, ICICI, Axis, Kotak formats) and new messages as they arrive.
- **Counts only your own spending**: investments, money lent, self-transfers, card-bill payments, rent and refunds are kept out of it.
- **Investments** are recognised from brokers, mutual-fund houses and fund confirmations (even bare "A/C debited" SMS are matched to the fund that sent the confirmation).
- **Limits**: daily, monthly and per category, with a notification at 80% and 100%.
- **Lent money**: lend from any payment, split a bill equally with friends, record repayments, send a reminder, and loans disappear once settled.
- **Refunds** from shops come off the purchase they refund (up to ₹50 short for fees the shop keeps).
- **Remembers your choices**: recategorise one payee and apply it to all their transactions and future SMS.
- **Find anything** by person, place, date, month, amount range, paid / received or type; add and edit transactions by hand; export to CSV.

## Privacy

Spendr has no internet permission. SMS are read and stored only on your phone, and nothing is ever uploaded.

## Build

Needs JDK 17 and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest assembleRelease
```

The installable APK is `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the local debug key so they install directly; use a real keystore before distributing.

## Install

Download `Spendr-1.0.apk` from [Releases](https://github.com/Gopesh456/Spendr-1.0/releases/latest) on your phone and open it. If Play Protect blocks it (it can for sideloaded apps that read SMS), install over USB instead:

```bash
adb install -r Spendr-1.0.apk
```

If the SMS permission is greyed out after a sideload, go to Settings → Apps → Spendr → ⋮ → Allow restricted settings.

## Contributing

Found an SMS Spendr reads wrong, or want to add a feature? See [CONTRIBUTING.md](CONTRIBUTING.md). Please redact personal details from any SMS you share.

## License

[MIT](LICENSE)
