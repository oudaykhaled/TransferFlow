# Two-minute reviewer walkthrough

Use the debug APK on an Android 7.0+ emulator or device. All names, accounts and money are fictional. A fresh install starts with **€12,480.55** available; previous demo transfers change this balance.

1. On the account screen, scroll to **Developer scenarios** and choose **Lost response**.
2. Tap **New transfer**, then **Fill fictional recipient**. The fixture sends **€25.00** to Alex Morgan, with no fee.
3. Tap **Review transfer**. Check the recipient, account, amount, fee and total. **Edit details** returns to the draft without submitting.
4. Tap **Confirm transfer**. The synthetic gateway accepts and debits the transfer, then loses its response. The app shows **Let’s confirm the outcome**, rather than assuming failure.
5. Force-stop the app from Android Settings and reopen it. The saved request is still unresolved, with its original details.
6. Tap **Retry safely**. The app looks up the original key before considering another submission. The operation resolves as **Transfer complete**.
7. Return to the account. There is one transfer in recent activity, and the available and booked balances are **€12,455.55** for this fresh-install walkthrough.

The visible operation reference identifies the accepted operation. Internal idempotency keys stay out of the product UI. The repository tests additionally verify unchanged keys and payloads, unique history, and exactly one debit across retry and reopen.

## Other useful checks

- **Pending:** acceptance reserves funds. Reopening retains the active request; **Check status** completes the same operation.
- **Rejected:** the selected fictional decline leaves the balance unchanged and lets the user start a new transfer after the terminal result.
- **Simulated offline:** confirmation is blocked before transport. An already uncertain operation remains uncertain until status can be checked.
- **Validation:** try an invalid Dutch IBAN, zero amount, three decimal places, or an amount above the available balance. The first error receives focus, including at 200% font size.
- **Slow response:** repeated taps cannot create a second active operation.

Developer scenario controls are compiled out of the release UI. Clearing app data resets both synthetic databases; it is a demo reset, not a recovery operation for a real bank.

## Engineering tour

Start with [the ownership decision](adr/0001-durable-transfer-ownership.md), then inspect `RoomTransferRepository`, `DemoTransferGateway`, and `TransferFlowViewModel`. The optional [HTTP contract](api/openapi.yaml) and MockWebServer tests describe a remote adapter; the default demo does not call a hosted service.

Executed commands and remaining limitations are recorded in [verification evidence](verification.md).
