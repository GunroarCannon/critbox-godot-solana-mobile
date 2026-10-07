extends SceneTree
## Headless check of SolanaMobileClient against the mock wallet: every call,
## every failure path, busy, cancel, session reuse. No network, no phone.
##   godot --headless --path demo --script tools/client_check.gd

var _fails := 0


func _initialize() -> void:
	_run.call_deferred()


func _run() -> void:
	await process_frame
	var w := SolanaMobileClient.new("Check", "https://example.com", "icon.png", "devnet", true)
	_expect(not w.is_native(), "uses the mock off-device")
	w.mock.delay_sec = 0.05

	var r := await w.connect_wallet()
	_expect(r.ok and w.public_key == w.mock.public_key, "connect sets the public key")
	_expect(w.auth_token.begins_with("mock-token-"), "connect stores an auth token")
	var token := w.auth_token

	r = await w.connect_wallet()
	_expect(r.ok and w.auth_token == token, "reconnect reuses the saved token")
	_expect(w.mock.requests_seen[-1][1].get("auth_token") == token, "token is sent to the wallet")

	r = await w.sign_in("example.com", "Log in")
	_expect(r.ok and r.has("sign_in") and Marshalls.base64_to_utf8(r.sign_in.signed_message).contains("Log in"),
			"sign in returns the signed SIWS message")

	r = await w.sign_messages(["hello", PackedByteArray([1, 2, 3])])
	_expect(r.ok and r.signatures.size() == 2, "sign_messages: one signature per message")
	_expect(Marshalls.base64_to_utf8(w.mock.requests_seen[-1][1].messages[0]) == "hello", "text messages go out as UTF-8")

	var built := w.build_transfer({"to": w.public_key, "amount": 5, "blockhash": "x"})
	_expect(built.ok and built.tx != "", "build_transfer returns a tx")
	_expect(not w.build_transfer({"amount": 5}).ok, "build_transfer rejects missing fields")

	r = await w.sign_and_send([built.tx])
	_expect(r.ok and r.signatures.size() == 1, "sign_and_send returns a signature")
	# A dead local port: the blockhash prefetch fails at once and the check stays offline.
	w.rpc_url = "http://127.0.0.1:9"
	r = await w.transfer(w.public_key, 1000, "", 0, "memo")
	_expect(r.ok and r.signatures.size() == 1, "transfer sends in one session")
	r = await w.transfer("not-a-key", 1000)
	_expect(not r.ok and r.code == "invalid_request", "transfer rejects a bad recipient before the wallet opens")

	r = await w.capabilities()
	_expect(r.ok and r.capabilities.has("max_transactions"), "capabilities")

	for code in ["cancelled", "declined", "no_wallet", "timeout"]:
		w.mock.next_error = code
		r = await w.connect_wallet()
		_expect(not r.ok and r.code == code, "failure '%s' reaches the caller" % code)
	_expect(w.auth_token == token, "a declined request keeps the session")
	w.mock.next_error = "unauthorized"
	r = await w.sign_messages(["x"])
	_expect(not r.ok and r.code == "unauthorized" and w.auth_token == "", "an unauthorized session token is dropped")

	# Busy: a second request while one is open fails at once.
	w.mock.delay_sec = 0.3
	var first := _hold(w)
	r = await w.sign_messages(["second"])
	_expect(not r.ok and r.code == "busy", "second request while busy is refused")
	await first.wait()
	_expect(first.result.ok, "the first request still completes")

	# Cancel ends the open request.
	var held := _hold(w)
	await create_timer(0.05).timeout
	w.cancel()
	await held.wait()
	_expect(not held.result.ok and held.result.code == "cancelled", "cancel ends the request in flight")

	r = await w.disconnect_wallet()
	_expect(w.public_key == "" and w.auth_token == "", "disconnect forgets the session")

	w.mock.wallet_installed = false
	_expect(not w.is_wallet_installed(), "wallet-installed reflects the device")
	r = await w.connect_wallet()
	_expect(not r.ok and r.code == "no_wallet", "no wallet installed -> no_wallet")

	print("client_check: %s" % ("PASS" if _fails == 0 else "FAIL (%d)" % _fails))
	quit(0 if _fails == 0 else 1)


## Starts a connect without awaiting it; [code]done[/code] fires when it finishes.
func _hold(w: SolanaMobileClient) -> Holder:
	var h := Holder.new()
	h.start(w)
	return h


class Holder:
	signal done
	var result := {}
	var finished := false

	func start(w: SolanaMobileClient) -> void:
		result = await w.connect_wallet()
		finished = true
		done.emit()

	## Signals resume awaiters synchronously, so it may already be over.
	func wait() -> void:
		if not finished:
			await done


func _expect(ok: bool, what: String) -> void:
	print(("  ok    " if ok else "  FAIL  ") + what)
	if not ok:
		_fails += 1
