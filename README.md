# critbox-godot-solana-mobile

**Solana Mobile Wallet Adapter for Godot 4.7 on Android.** Connect, Sign-In-With-Solana,
sign messages, and sign and send transactions with any MWA wallet: Phantom, Solflare,
Backpack, or the Seed Vault wallet on a Solana **Seeker**. Pay with SOL or any SPL token
(SKR included) straight from GDScript.

- **Signal-driven, never polls.** Every wallet call is a single `await`.
- **Mock wallet included.** The same game code runs in the editor, on desktop and in
  headless CI, and you can script cancels, declines and missing wallets.
- **Builds transfers natively.** SOL, or SPL / Token-2022 with the receiver's token account
  created idempotently, plus an optional memo. Checked byte for byte against `@solana/web3.js`.
- **Seeker-aware.** `device_info()` reports Solana Mobile hardware, the Seeker model and
  whether Seed Vault is present.
- **Pinned to the Godot 4.7 Android build template** (Kotlin 2.1.21, AGP 8.6.1), so it
  drops in without dependency fights.

Made by [Critbox](https://github.com/GunroarCannon) for the CLOCK IN Solana Mobile
hackathon. MIT licensed.

## Quick start

1. Copy `demo/addons/solana_mobile/` into your project's `addons/`, including the
   `bin/` AARs from a [release](../../releases) or your own build.
2. **Project → Project Settings → Plugins**: enable *Solana Mobile*.
3. **Project → Install Android Build Template**, then tick **Use Gradle Build** on your
   Android export preset, along with the INTERNET permission.
4. Use it:

```gdscript
var wallet := SolanaMobileClient.new("My Game", "https://mygame.example", "favicon.ico", "devnet")

var r := await wallet.sign_in("mygame.example", "Sign in to My Game")
if not r.ok:
	print("wallet said: ", r.code)   # cancelled, declined, no_wallet, …
	return
print("hello ", wallet.public_key)

# Pay 5 SKR (6 decimals) with a memo your server can match:
const SKR := "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
var paid := await wallet.transfer(TREASURY, 5_000_000, SKR, 6, "order:42")
if paid.ok:
	print("https://explorer.solana.com/tx/", paid.signatures[0])
```

Save `wallet.auth_token` and `wallet.public_key`. Restoring them on the next launch
reconnects without asking for approval again.

## API (`SolanaMobileClient`)

| Call | Result on success |
|---|---|
| `await connect_wallet()` | `public_key`, `auth_token`, `accounts` |
| `await sign_in(domain, statement)` | as connect, plus `sign_in.signed_message` / `sign_in.signature` (base64) for your server to verify |
| `await sign_messages(["text" or PackedByteArray, …])` | `signatures` (base64) |
| `await sign_transactions([tx_b64, …])` | `signed_transactions` (base64) |
| `await sign_and_send([tx_b64, …])` | `signatures` (base58) |
| `await transfer(to, amount, mint := "", decimals := 0, memo := "")` | build + sign and send in one wallet session; the blockhash is refreshed after the wallet opens where Android allows it (it only lives ~36 s on devnet, ~60 s on mainnet) |
| `build_transfer({to, amount, blockhash, mint?, decimals?, memo?, create_ata?, token_program?})` | `tx` (base64, unsigned) |
| `await capabilities()` | `capabilities` |
| `await disconnect_wallet()` / `forget()` | session revoked / cleared locally |
| `cancel()` | the open request ends as `cancelled` |
| `is_wallet_installed()`, `device_info()`, `is_native()`, `is_busy()` | sync |
| `await rpc(method, params)`, `latest_blockhash()`, `balance()`, `token_balance(mint)` | JSON-RPC helpers on `rpc_url` |

Every awaited call returns `{ok: true, …}` or `{ok: false, code, message}`. The codes are
`busy`, `cancelled`, `no_wallet`, `declined` (said no to this request), `unauthorized`
(the saved session was revoked; the client forgets it), `not_submitted`, `timeout`,
`connection_failed`, `cluster_not_supported`, `too_many_payloads`, `invalid_payloads`,
`invalid_request`, `rpc_error` and `error`.

Off-device, `wallet.mock` is a `SolanaMobileMock`. Set `next_error`, `delay_sec`,
`wallet_installed` or `device` to test each path.

## How it works

MWA's `ActivityResultSender` must be registered before its activity starts, which
Godot's activity is long past by the time a game asks to connect. So every request
opens a **transparent trampoline activity** (`MwaBridgeActivity`). It creates the sender,
runs one MWA session against the wallet, reports back through a Godot signal, and
finishes. If the trampoline closes early, the request reports `cancelled`, so it never
hangs. Only Strings and ints cross JNI; bytes travel as base64 inside JSON.

See [`docs/PLAN.md`](docs/PLAN.md) for the full design and milestones.

## Build

Needs JDK 17 and the Android SDK (`local.properties` → `sdk.dir`).

```sh
./gradlew :plugin:testDebugUnitTest :plugin:assembleDebug :plugin:assembleRelease
# AARs land in demo/addons/solana_mobile/bin/{debug,release}/
godot --headless --path demo --script tools/client_check.gd   # mock-wallet check
```

If Maven Central refuses your network (HTTP 403), build through Google's mirror with
`-PmavenCentralMirror=https://maven-central.storage-download.googleapis.com/maven2/`.

The `demo/` project is a button-per-call test app. Export it with the *Android* preset
and try it on devnet.

## Credits

Built on Solana Mobile's [mobile-wallet-adapter](https://github.com/solana-mobile/mobile-wallet-adapter)
(`clientlib-ktx` 2.0.8) and `web3-solana`. The trampoline-activity idea first appeared in
the Godot world in [godot-solana-sdk](https://github.com/Virus-Axel/godot-solana-sdk)'s
`WalletAdapterAndroid`.
