# Contributing to Spendr

Thanks for helping! Bug reports, bank SMS formats and pull requests are all welcome.

## Reporting a bank SMS that's read wrong

Spendr's accuracy depends on knowing how each bank words its messages. If a transaction comes out with the wrong amount, direction, name or category, open an issue with:

- the bank / sender ID (e.g. `JD-IDFCFB-S`),
- the SMS text **with your private details replaced**: account digits, names, phone numbers, UPI IDs and reference numbers,
- what Spendr showed and what it should have shown.

Never post a real SMS unredacted.

## Making a change

1. Fork the repo and create a branch from `main`.
2. Build and run the tests (JDK 17 + Android SDK 36):

   ```bash
   ./gradlew testDebugUnitTest assembleRelease
   ```

3. For SMS parsing changes, add a test case to `app/src/test/java/app/spendr/SmsParserTest.kt` using a made-up message in the bank's real format, and make sure every existing test still passes.
4. Keep pull requests small and focused, and describe what you changed and why.

## Where things live

| File | What it does |
|------|--------------|
| `SmsParser.kt` | Reads amount, direction, payee and category from an SMS (pure Kotlin, unit tested) |
| `Sms.kt` | Inbox import, live SMS receiver, fund / refund hints |
| `Model.kt` | Transactions, loans, splits, refunds, spending stats, filters |
| `Db.kt` | SQLite storage and re-sorting old SMS when the rules improve |
| `*Screen.kt`, `EditSheet.kt`, `SplitSheet.kt` | Jetpack Compose UI |

## Code style

Plain Kotlin, no new libraries without a good reason, and comments only for the *why*. Everything stays on the device: no network calls, analytics or trackers.

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
